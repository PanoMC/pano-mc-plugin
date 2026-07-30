package com.panomc.plugins.pano.bungee

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.Integration
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.integration.BanIntegration
import com.panomc.plugins.pano.core.integration.PermissionIntegration
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import com.panomc.plugins.pano.core.platform.message.response.PermissionsSnapshotUpdatedMessage
import io.vertx.core.http.WebSocket
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.scheduler.ScheduledTask

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class BungeeMain : Plugin(), PanoPluginMain {
    // panoInitLock is held by getPano() ONLY -- across its entire body, including the blocking
    // Pano.init() call -- and by nothing else in this class. That makes getPano() single-flight:
    // at most one Pano is ever under construction at a time, so there is never a second, losing
    // construction left over to dispose of afterwards (see getPano()). This is deadlock-free
    // because the calls Pano.init()'s own callback graph makes back into this class while a
    // thread is parked in its runBlocking { deployVerticle(...) } are closed and small:
    // CommandManager.init() -> registerCommands() and EventManager.init() ->
    // registerEventListeners() (both take registrationLock only), plus
    // getServerData()/getPanoLogger()/getDataFolder()/getPluginClassLoader() (no lock at all) --
    // enumerated from Pano.init()'s own init() call graph (initDependencyInjection() ->
    // initCommandManager()/initEventManager()). None of them calls getPano(), so none of them can
    // ever block waiting on panoInitLock.
    //
    // lifecycleLock guards the construct/teardown bookkeeping (mPano, integrations, disabled,
    // startTask) and registrationLock guards the command/listener registrations those callbacks
    // touch. Symmetrically to panoInitLock/Pano.init(), neither of these two is ever held across
    // Pano.disable() (see onDisable()): that call runs Pano.closeVertx() ->
    // runBlocking { vertx.close().coAwait() }, and the undeploy it blocks on calls back into this
    // class from the Vert.x event loop -- CommandManager.disable() -> unregisterCommands(),
    // EventManager.disable() -> unregisterEventListeners() (both registrationLock), and
    // PlatformManager.stop() -> onDisconnect() -> every Integration.onDisconnect() (no lock at
    // all). registrationLock never needs to wait on Vert.x, a coroutine, panoInitLock or
    // lifecycleLock, so it can be held for the whole (fast, local) duration of each callback. As
    // long as that split holds, none of the three locks can ever be the thing an event-loop
    // callback is blocked on while its own thread is the one Pano.init()/disable() is waiting for.
    private val panoInitLock = Any()
    private val lifecycleLock = Any()
    private val registrationLock = Any()

    // Guarded by lifecycleLock. Not a `by lazy`: onDisable() calls mPano.disable(), which closes
    // the shared static Vert.x. A lazy delegate memoizes permanently, so a runtime re-enable of
    // this same plugin instance (e.g. PlugMan) would reuse that dead Pano forever. getPano()
    // (re)builds it on demand instead; null also doubles as the "never constructed" guard
    // onDisable() needs, since onServerStart() runs on a scheduler task (see onEnable) and may
    // never fire (proxy/plugin disabled before it runs) — touching mPano unconditionally in
    // onDisable() would otherwise construct a whole Vert.x instance + Spring context during
    // shutdown only to immediately close it.
    private var mPano: Pano? = null

    // Guarded by lifecycleLock. Flipped to true inside onDisable()'s lock block once teardown has
    // committed to tearing mPano down, and cleared at the start of the following onEnable(). Lets
    // getPano() refuse to resurrect a Pano — and dispose of the one it just built via Pano.init(),
    // and close the Vert.x it may have had to create for it — if onDisable() finished while that
    // construction was in flight (see getPano()).
    private var disabled = false

    // Guarded by lifecycleLock. Bumped once at the start of every onEnable() and once inside every
    // onDisable(), so any *completed* disable-then-enable cycle changes the value even though
    // `disabled` itself is cleared back to false by that same onEnable(). getPano() snapshots this
    // before calling Pano.init() and compares it against the current value afterwards: `disabled`
    // alone can't catch a full cycle that finishes while a thread is parked in Pano.init(), because
    // that thread would wake up to find disabled == false again and publish an instance belonging to
    // the enable cycle that already tore its event listener down (see getPano()).
    private var enableGeneration = 0

    // Guarded by lifecycleLock. The one-off onServerStart() boot task: cancelled in onDisable() so
    // a shutdown landing mid-boot doesn't leave it free to call getPano() after teardown started.
    private var startTask: ScheduledTask? = null

    // Guarded by registrationLock. Keyed by the Command passed to registerCommands() (identity --
    // Command has no equals/hashCode override, and CommandManager passes the same list instance to
    // both registerCommands()/unregisterCommands() for a given Pano instance's lifetime, so identity
    // is enough) so unregisterCommands(commands) can target exactly the BungeeCommand wrappers it
    // registered for those commands, instead of BungeeCord's PluginManager.unregisterCommands(Plugin)
    // -- all-or-nothing per plugin -- which would tear down every command this main has ever
    // registered, not just the ones a particular unregisterCommands() call was passed.
    private val registeredCommands = mutableMapOf<Command, BungeeCommand>()

    private val serverData by lazy { BungeeServerData(this) }

    // Guarded by registrationLock: only ever read or written from inside
    // registerEventListeners()/unregisterEventListeners()/onDisable(), all of which take that lock.
    internal lateinit var bungeeEventListener: BungeeEventListener

    // Guarded by registrationLock, same as bungeeEventListener. Mirrors bungeeEventListener's
    // assigned state but, unlike ::bungeeEventListener.isInitialized, is reset to false once the
    // listener is actually unregistered (onDisable(), see below) so a later registerEventListeners()
    // builds and re-registers a fresh listener instead of assuming a stale one is still live.
    private var eventListenerRegistered = false

    // Long-lived scope for command handlers (see BungeeCommand): created in onEnable, cancelled in
    // onDisable, so nothing outlives the plugin and a throwing handler is always logged instead of
    // only producing an uncaught-handler dump. @Volatile: reassigned on every onEnable (proxy
    // boot thread) and read from command-dispatch/Netty connection threads (BungeeCommand.execute,
    // BungeeEventListener.onPreLogin) with no lock guarding either side, so those threads need a
    // guaranteed-visible read of the current instance rather than a possibly-stale cached one.
    @Volatile
    internal lateinit var coroutineScope: CoroutineScope
        private set

    // Rebuilt on every enable, not `by lazy`: PermissionIntegration/BanIntegration memoize the Pano
    // instance and its managers via their own `by lazy` fields, so keeping the same integration
    // objects around after a re-enable would leave them wired to the Pano/PlatformManager this
    // instance's onDisable() already tore down instead of the fresh one getPano() builds next.
    // Written under lifecycleLock (onEnable()/onDisable()); @Volatile so
    // onConnectionEstablished/onDisconnect/onServerSettingsChanged/onPermissionsSnapshotUpdated can
    // read it from the Vert.x event loop without taking any lock at all (see those methods below).
    @Volatile
    private var integrations: List<Integration> = emptyList()

    override fun onEnable() {
        coroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
                logger.warning("Unhandled coroutine exception: ${throwable.message}")
            }
        )

        val newIntegrations = listOf(
            PermissionIntegration(this),
            BanIntegration(this),
        )

        synchronized(lifecycleLock) {
            disabled = false
            enableGeneration++
            integrations = newIntegrations
        }

        newIntegrations.forEach { it.onEnable() }

        // Pano.init() (triggered by touching mPano) blocks in runBlocking { deployVerticle(...) }, and
        // PlatformManager.start() retries a failed connect through nested runBlocking calls on the
        // calling thread. Running that inline here would block BungeeCord's boot thread before it
        // reaches startListeners(), so no listener port ever binds. Hop onto the scheduler pool
        // instead, mirroring VelocityMain.onEnable().
        synchronized(lifecycleLock) {
            startTask = proxy.scheduler.schedule(this, Runnable { getPano().onServerStart() }, 0, TimeUnit.SECONDS)
        }
    }

    // Neither lifecycleLock nor registrationLock may be held across mPano.disable() below (it runs
    // Pano.closeVertx() -> runBlocking { vertx.close().coAwait() }, blocking this thread until the
    // event loop finishes undeploying) or across pano?.disable() in general, since that undeploy
    // calls back into this class from the Vert.x event loop — see the class-level comment. This
    // method never touches panoInitLock at all (see the class-level comment: it is getPano()'s
    // alone). So this method only ever takes lifecycleLock or registrationLock for a bounded, local
    // mutation, and always releases it before making a blocking call: lifecycleLock to
    // capture-and-clear mPano/integrations/startTask/disabled, then registrationLock (via the block
    // below) to actually unregister the event listener, then neither of those two for the
    // integration callbacks and pano.disable() itself. (Not a guarantee about every lock in the
    // process, though:
    // PermissionIntegration/BanIntegration's own `pano by lazy { panoPluginMain.getPano() }` still
    // holds that lazy delegate's own monitor across its first, initializing getPano() call if
    // nothing had touched it yet by the time that integration's onDisable() runs -- a lock this
    // class doesn't own or control.)
    override fun onDisable() {
        val pano: Pano?
        val integrationsToDisable: List<Integration>

        synchronized(lifecycleLock) {
            // Cancel the deferred start task first: if it never ran, mPano must stay unconstructed
            // (see the null check below); if it's mid-flight inside getPano(), disabled=true here
            // makes it abort and clean up the Pano it builds instead of publishing (and resurrecting)
            // one after this method has already finished tearing down (see getPano()).
            startTask?.cancel()
            startTask = null
            disabled = true
            enableGeneration++

            // Null check doubles as the isInitialized-equivalent guard: skip disable() entirely when
            // mPano was never constructed, and clear the field so a later re-enable of this same
            // instance builds a fresh Pano instead of reusing the one this method is about to tear
            // down.
            pano = mPano
            mPano = null

            integrationsToDisable = integrations
            integrations = emptyList()
        }

        // Integrations before Pano, the inverse of onEnable, and with no lock held: mPano.disable()
        // closes Vert.x asynchronously, and integration teardown should not race that. Each
        // integration gets its own failure boundary so one broken onDisable() can't skip the rest.
        integrationsToDisable.forEach { integration ->
            runCatching { integration.onDisable() }
                .onFailure { exception ->
                    logger.warning("Failed to disable integration ${integration.javaClass.simpleName}: ${exception.message}")
                }
        }

        // Registrations next. BungeeCord does NOT drop a plugin's registered listeners on a
        // runtime re-enable (e.g. PlugMan) — only unregisterListener(s) does that — so this must
        // actually unregister rather than just clear the flag; otherwise a later
        // registerEventListeners() (see below) registers a second, independent
        // BungeeEventListener while this one is still live with the proxy, and every login runs
        // the ban check twice, one of them against a torn-down Pano.
        synchronized(registrationLock) {
            if (eventListenerRegistered) {
                proxy.pluginManager.unregisterListener(bungeeEventListener)
                eventListenerRegistered = false
            }
        }

        // Pano last, no lock held: see the method comment.
        pano?.disable()

        // Guard isInitialized: onDisable() can be reached even if onEnable() never finished
        // assigning it (e.g. it threw before this line).
        if (::coroutineScope.isInitialized) {
            coroutineScope.cancel()
        }
    }

    override fun registerCommands(commands: List<Command>) {
        synchronized(registrationLock) {
            commands.forEach { command ->
                val bungeeCommand = BungeeCommand(command, this)

                registeredCommands[command] = bungeeCommand
                proxy.pluginManager.registerCommand(this, bungeeCommand)
            }
        }
    }

    // Idempotent: a command missing from registeredCommands (already unregistered, or never
    // registered by this instance) is simply skipped. Only ever unregisters the BungeeCommand
    // wrappers this instance itself registered for the passed-in commands --
    // proxy.pluginManager.unregisterCommand(Command) (singular; unlike the all-or-nothing
    // unregisterCommands(Plugin) this replaces) takes the specific registered Command object, so
    // this can never take down a command it did not itself register.
    override fun unregisterCommands(commands: List<Command>) {
        synchronized(registrationLock) {
            commands.forEach { command ->
                registeredCommands.remove(command)?.let { bungeeCommand ->
                    proxy.pluginManager.unregisterCommand(bungeeCommand)
                }
            }
        }
    }

    override fun getServerData(): ServerData = serverData

    override fun getPluginClassLoader(): java.net.URLClassLoader = javaClass.classLoader as java.net.URLClassLoader

    override fun translateColor(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

    override fun registerEventListeners(listeners: Set<Listener>) {
        synchronized(registrationLock) {
            // eventListenerRegistered, not ::bungeeEventListener.isInitialized: the latter stays true
            // forever after the first assignment, which would make a re-enable reuse a
            // BungeeEventListener that onDisable() already unregistered (see the field comment)
            // instead of building and registering a fresh one.
            if (eventListenerRegistered) {
                bungeeEventListener.listeners.addAll(listeners)
                return
            }

            // CopyOnWriteArraySet: registerEventListeners/unregisterEventListeners mutate this set
            // under registrationLock while onPreLogin iterates it lock-free on Netty connection
            // threads during login — a plain LinkedHashSet is neither thread-safe nor guaranteed
            // visible across those threads.
            bungeeEventListener = BungeeEventListener(this, CopyOnWriteArraySet(listeners))

            proxy.pluginManager.registerListener(this, bungeeEventListener)
            eventListenerRegistered = true
        }
    }

    override fun unregisterEventListeners(listeners: Set<Listener>) {
        synchronized(registrationLock) {
            // eventListenerRegistered is only set once registerEventListeners() runs and onDisable()
            // resets it; onDisable() can reach here (via PermissionIntegration.onDisable()) even when
            // that never fired, or after onDisable() already reset it.
            if (!eventListenerRegistered) return

            bungeeEventListener.listeners.removeAll(listeners)
        }
    }

    override fun getPanoLogger(): Logger = logger

    override fun getPano(): Pano {
        // Single-flight: everything from the disabled/already-published check through publishing
        // `created` (or aborting it) runs under panoInitLock, so at most one thread is ever inside
        // Pano.init() at a time and there is never a second, losing construction to reconcile
        // against a winner afterwards. See the class-level comment for why holding this across
        // Pano.init() cannot deadlock.
        synchronized(panoInitLock) {
            val generationAtStart: Int

            synchronized(lifecycleLock) {
                mPano?.let { return it }
                check(!disabled) { "Pano requested after BungeeMain was disabled" }
                generationAtStart = enableGeneration
            }

            // Neither lifecycleLock nor registrationLock is held across this: Pano.init() blocks in
            // runBlocking { deployVerticle(...) }, and the verticle's start() calls back into this
            // class (CommandManager.init() -> registerCommands(), EventManager.init() ->
            // registerEventListeners()) from the Vert.x event loop while this thread is parked here
            // — see the class-level comment for the full enumeration. (Only this class's own locks,
            // though: if this call was reached through an integration's
            // `pano by lazy { panoPluginMain.getPano() }` being touched for the first time, that
            // lazy delegate's own monitor is held by this same thread for the whole call — a lock
            // this class doesn't own or control.)
            val created = Pano.init(this)

            synchronized(lifecycleLock) {
                // disabled alone isn't enough: onEnable() clears it back to false at the start of
                // every enable, so a COMPLETE disable-then-re-enable cycle that finishes while this
                // thread was parked in Pano.init() above would otherwise leave `disabled == false`
                // by the time we get here, and `created` would get published as if it belonged to
                // the current (re-enabled) cycle even though it's actually an orphan of the PREVIOUS
                // one -- one whose event listener onDisable() already unregistered. The generation
                // check catches that: it changes on every completed enable/disable transition, so a
                // mismatch here means at least one full cycle happened while we were inside
                // Pano.init(), regardless of what `disabled` reads now. The explicit disabled check
                // is kept too, for the ordinary (non-cycled) abort-after-disable case and to keep the
                // fast path honest.
                if (disabled || enableGeneration != generationAtStart) {
                    // Abort-after-disable orphan: onDisable() ran to completion while this thread
                    // was inside Pano.init() above, so `created` must never be published. dispose()
                    // undeploys its verticle -- and, with no race-loser flag left for stop() to
                    // special-case, correctly unregisters exactly the commands/listeners this
                    // instance itself registered. getOrCreateVertx() inside Pano.init() may also
                    // have had to hand `created` a brand-new Vert.x, since onDisable() already
                    // closed whatever the previous one was: closeVertxForAbortedInit() closes that
                    // instance in the background (never blocking, and only if it's still the one
                    // installed in the companion's Vert.x reference) so its non-daemon event-loop/
                    // worker threads can't hang JVM shutdown.
                    created.disposeAndCloseVertxForAbortedInit()

                    error("Pano requested after BungeeMain was disabled")
                }

                mPano = created
            }

            return created
        }
    }

    // Each integration gets its own failure boundary, mirroring SpigotMain: without it, one
    // integration throwing here would take out the rest of the fan-out, and since this runs on the
    // Vert.x event loop (via PlatformManager.onWebSocketClosed() -> pluginMain.onDisconnect(), etc.)
    // that would surface as an unhandled event-loop exception instead of a logged warning.
    override fun onConnectionEstablished(webSocket: WebSocket?) {
        // Unlike the other fan-outs, this one must not swallow. PlatformManager wraps its call to this
        // method in a try/catch whose whole purpose is to notice a half-wired connection and abort into
        // the reconnect path; eating the exception here would leave the socket up with integrations only
        // partially hooked and nothing retrying. So keep the per-integration boundary -- every
        // integration still gets its callback even if an earlier one throws -- but rethrow the first
        // failure once the loop is done.
        var firstFailure: Exception? = null

        integrations.forEach {
            try {
                it.onConnectionEstablished(webSocket)
            } catch (exception: Exception) {
                logger.warning("Integration ${it.javaClass.simpleName} failed to handle connection established: ${exception.message}")

                if (firstFailure == null) {
                    firstFailure = exception
                }
            }
        }

        firstFailure?.let { throw it }
    }

    override fun onDisconnect() {
        integrations.forEach {
            try {
                it.onDisconnect()
            } catch (exception: Exception) {
                logger.warning("Integration ${it.javaClass.simpleName} failed to handle disconnect: ${exception.message}")
            }
        }
    }

    override fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {
        integrations.forEach {
            try {
                it.onServerSettingsChanged(serverSettings)
            } catch (exception: Exception) {
                logger.warning("Integration ${it.javaClass.simpleName} failed to handle server settings change: ${exception.message}")
            }
        }
    }

    override fun onPermissionsSnapshotUpdated(message: PermissionsSnapshotUpdatedMessage) {
        integrations.forEach {
            try {
                it.onPermissionsSnapshotUpdated(message)
            } catch (exception: Exception) {
                logger.warning("Integration ${it.javaClass.simpleName} failed to handle permissions snapshot update: ${exception.message}")
            }
        }
    }

    override fun kickPlayer(player: String, message: String) {
        // disconnect(String) only translates the section sign; callers (e.g. BanPlayerHandler)
        // pass raw '&'-coded messages, so translate here first, mirroring translateColor's other
        // call sites.
        proxy.getPlayer(player)?.disconnect(TextComponent(translateColor(message)))
    }
}
