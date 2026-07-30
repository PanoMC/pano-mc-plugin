package com.panomc.plugins.pano.velocity

import com.google.inject.Inject
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
import com.panomc.plugins.pano.core.util.LegacyColorConverter
import com.panomc.plugins.pano.velocity.integration.LimboAuthIntegration
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyReloadEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import io.vertx.core.http.WebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.io.File

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArraySet
import java.util.logging.Logger

class VelocityMain : PanoPluginMain {
    // panoInitLock is held by getPano() ONLY -- across its entire body, including the blocking
    // Pano.init() call -- and by nothing else in this class. That makes getPano() single-flight:
    // at most one Pano is ever under construction at a time, so there is never a second, losing
    // construction left over to reconcile against a winner afterwards (see getPano()). This is
    // deadlock-free because the calls Pano.init()'s own callback graph makes back into this class
    // while a thread is parked in its runBlocking { deployVerticle(...) } are closed and small:
    // CommandManager.init() -> registerCommands() and EventManager.init() ->
    // registerEventListeners() (both take registrationLock only), plus
    // getDataFolder()/getPanoLogger()/getServerData()/getPluginClassLoader() (no lock at all) --
    // enumerated from Pano.init()'s own init() call graph (initDependencyInjection() ->
    // initCommandManager()/initEventManager()). None of them calls getPano(), so none of them can
    // ever block waiting on panoInitLock.
    //
    // lifecycleLock/registrationLock are the two distinct monitors so that nothing reachable from
    // Pano.disable()'s callbacks into this class can ever block on either while a thread is parked
    // inside that blocking, event-loop-re-entrant call. Neither is ever held across it — see
    // onDisable() below. (Not a guarantee about every lock in the process, though: an integration's
    // own `pano by lazy { panoPluginMain.getPano() }` -- see LimboAuthIntegration -- still holds
    // that lazy delegate's own monitor across its first, initializing getPano() call; that lock
    // belongs to the integration, not to this class, and isn't covered by the split below.)
    //   lifecycleLock    guards: mPano, integrations, disabled, startTask
    //   registrationLock guards: commands, velocityEventListener + eventListenerRegistered
    private val panoInitLock = Any()
    private val lifecycleLock = Any()
    private val registrationLock = Any()

    // Not a `by lazy`: onDisable() tears Pano down completely (Vert.x is closed), so a following
    // onEnable() — Velocity fires ProxyReloadEvent — has to build a brand new instance instead of
    // reusing the dead one. Guarded by lifecycleLock.
    private var mPano: Pano? = null

    // Guards getPano() against resurrecting a Pano after onDisable() has torn one down: flipped to
    // true inside onDisable()'s lifecycleLock block once teardown reaches the point where mPano is
    // no longer safe to touch, and cleared at the start of the following onEnable(). Guarded by
    // lifecycleLock; @Volatile besides so the unlocked quick-check in the onEnable() boot task
    // below can see a fresh value without taking the lock.
    @Volatile
    private var disabled = false

    // Guarded by lifecycleLock. Bumped once at the start of every onEnable() and once inside every
    // onDisable(), so any *completed* disable-then-enable cycle changes the value even though
    // `disabled` itself is cleared back to false by that same onEnable(). getPano() snapshots this
    // before calling Pano.init() and compares it against the current value afterwards: `disabled`
    // alone can't catch a full cycle finishing while a thread is parked in Pano.init() -- and on
    // Velocity that's not hypothetical, `/velocity reload` fires ProxyReloadEvent, whose handler is
    // exactly onDisable() followed by onEnable() -- because that thread would wake up to find
    // disabled == false again and publish an instance belonging to the enable cycle that already
    // tore its event listener down (see getPano()).
    private var enableGeneration = 0

    // Guarded by registrationLock. Keyed by the Command passed to registerCommands() (identity --
    // Command has no equals/hashCode override, and CommandManager passes the same list instance to
    // both registerCommands()/unregisterCommands() for a given Pano instance's lifetime, so identity
    // is enough) so unregisterCommands(commands) can target exactly the entries it was passed,
    // instead of every command this VelocityMain has ever registered.
    private val commands = mutableMapOf<Command, Pair<VelocityCommand, CommandMeta>>()
    // The one-off onServerStart() boot task: cancelled on onDisable() so a reload landing mid-boot
    // can't have it fire getPano().onServerStart() after Pano was already torn down. Guarded by
    // lifecycleLock.
    private var startTask: ScheduledTask? = null
    // Guarded by registrationLock. Also @Volatile: written under registrationLock in
    // registerEventListeners()/onDisable(), read from the Vert.x event loop (onConnectionEstablished/
    // onDisconnect/onServerSettingsChanged fan out through integrations, whose listeners reach into
    // this field — see LimboAuthIntegration) which per R4 must take no lock at all.
    // Without this a reload's fresh assignment can stay invisible to the event loop.
    @Volatile
    internal lateinit var velocityEventListener: VelocityEventListener
    @Volatile
    private var eventListenerRegistered = false

    // Long-lived scope for detached coroutine work (currently: /pano command handlers doing
    // blocking I/O). A fresh instance is (re)built in onEnable() and cancelled in onDisable(), the
    // same lifecycle as mPano/startTask, so a reload swaps in a live scope and no command
    // coroutine can outlive the plugin instance and hit the disabled check in getPano() uncaught.
    // @Volatile: reassigned in onEnable() (not under any lock VelocityCommand.execute takes) and
    // read from arbitrary command-dispatch threads via pluginMain.coroutineScope.launch, so without
    // this a command thread could observe a stale, already-cancelled scope from before a
    // ProxyReloadEvent. A single reference write/read, so @Volatile alone is sufficient.
    @Volatile
    internal var coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private set

    // Rebuilt on every enable as well: the integrations cache the Pano instance and its managers,
    // so keeping the old objects around after a reload would leave them pointing at a dead Pano.
    // Written under lifecycleLock in onEnable()/onDisable(); also @Volatile because it's read
    // lock-free (R4) from the Vert.x event loop via onConnectionEstablished/onDisconnect/
    // onServerSettingsChanged/onPermissionsSnapshotUpdated, so a reload's swap must be visible
    // there without a lock.
    @Volatile
    private var integrations: List<Integration> = emptyList()

    @Inject
    private lateinit var server: ProxyServer

    @Inject
    private lateinit var logger: Logger

    @Inject
    @DataDirectory
    private lateinit var dataFolder: Path

    private val serverData by lazy { VelocityServerData(server) }

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        onEnable()
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        onDisable()
    }

    @Subscribe
    fun onProxyReload(event: ProxyReloadEvent) {
        onDisable()
        onEnable()
    }

    override fun getDataFolder(): File = dataFolder.toFile()

    override fun getPanoLogger(): Logger = logger

    // Not @Synchronized as a whole — see the panoInitLock/lifecycleLock/registrationLock note on
    // the class. Pano.init() below does `runBlocking { vertx.deployVerticle(pano).coAwait() }`: it
    // blocks this thread while the deployment runs on the Vert.x event loop and calls back into
    // this class (CommandManager.init() -> registerCommands(), EventManager.init() ->
    // registerEventListeners(), both registrationLock). panoInitLock is held across that call on
    // purpose (see the class-level comment for why that's deadlock-free and what makes it
    // single-flight); lifecycleLock is only ever held here for the plain field reads/writes below,
    // never across Pano.init() itself.
    override fun getPano(): Pano {
        synchronized(panoInitLock) {
            val generationAtStart: Int

            synchronized(lifecycleLock) {
                mPano?.let { return it }
                check(!disabled) { "Pano requested after VelocityMain was disabled" }
                generationAtStart = enableGeneration
            }

            val created = Pano.init(this)

            synchronized(lifecycleLock) {
                // disabled alone isn't enough: onEnable() clears it back to false at the start of
                // every enable, so a COMPLETE disable-then-re-enable cycle (e.g. a `/velocity
                // reload`, whose ProxyReloadEvent handler is exactly onDisable() followed by
                // onEnable()) that finishes while this thread was parked in Pano.init() above would
                // otherwise leave `disabled == false` by the time we get here, and `created` would
                // get published as if it belonged to the current (re-enabled) cycle even though it's
                // actually an orphan of the PREVIOUS one -- one whose event listener onDisable()
                // already unregistered. The generation check catches that: it changes on every
                // completed enable/disable transition, so a mismatch here means at least one full
                // cycle happened while we were inside Pano.init(), regardless of what `disabled`
                // reads now. The explicit disabled check is kept too, for the ordinary (non-cycled)
                // abort-after-disable case and to keep the fast path honest.
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
                    // worker threads can't hang the JVM at shutdown.
                    created.disposeAndCloseVertxForAbortedInit()

                    error("Pano requested after VelocityMain was disabled")
                }

                mPano = created
            }

            return created
        }
    }

    internal fun getServer(): ProxyServer = server

    private fun onEnable() {
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val newIntegrations = listOf(
            PermissionIntegration(this),
            BanIntegration(this),
            LimboAuthIntegration(this),
        )

        synchronized(lifecycleLock) {
            disabled = false
            enableGeneration++
            integrations = newIntegrations
        }

        newIntegrations.forEach { it.onEnable() }

        val task: () -> Unit = {
            // A reload/shutdown can land between scheduling and this task actually firing (or while
            // it is blocked inside getPano(), e.g. behind onDisable()'s brief hold of lifecycleLock);
            // check the flag so a stale task doesn't resurrect a Pano that was just torn down.
            // Unlocked read of the @Volatile flag is only an optimization — getPano() re-checks under
            // lifecycleLock.
            if (!disabled) {
                getPano().onServerStart()
            }
        }

        val scheduled = server.scheduler
            .buildTask(this, task)
            .schedule()

        synchronized(lifecycleLock) {
            startTask = scheduled
        }
    }

    // Not @Synchronized as a whole, and neither lifecycleLock nor registrationLock is ever held
    // across pano?.disable() below — see the class-level note. This method never touches
    // panoInitLock at all (see the class-level comment: it is getPano()'s alone).
    // pano.disable() runs Pano.closeVertx(), which does `runBlocking { vertx.close().coAwait() }`:
    // it blocks this thread until Vert.x finishes undeploying the Pano verticle, and undeploy runs
    // Pano.stop() on the Vert.x event loop, which calls back into this class — CommandManager.disable()
    // -> unregisterCommands(), EventManager.disable() -> unregisterEventListeners() (both
    // registrationLock), PlatformManager.stop() -> onDisconnect() -> Integration.onDisconnect()
    // (no lock at all). If this thread held registrationLock (or lifecycleLock) while blocked in
    // runBlocking, those event-loop calls would block forever on a lock only this (blocked) thread
    // can release — a guaranteed deadlock. So state is captured into locals under each lock and both
    // locks are fully released before pano.disable() is called. (Not a guarantee about every lock in
    // the process, though: an integration's own `pano by lazy { panoPluginMain.getPano() }` still
    // holds that lazy delegate's own monitor across its first, initializing getPano() call if
    // nothing had touched it yet by the time that integration's onDisable() runs — a lock this class
    // doesn't own or control.)
    private fun onDisable() {
        val pano: Pano?
        val integrationsSnapshot: List<Integration>

        synchronized(lifecycleLock) {
            startTask?.cancel()
            startTask = null

            integrationsSnapshot = integrations
            integrations = emptyList()

            // Flip after capturing integrations above (whose onDisable() below still needs a live
            // Pano/managers) but before mPano is released, so any getPano() call still mid-flight in
            // Pano.init() — e.g. a boot task from a previous onEnable — sees the disabled state on
            // its next lifecycleLock check instead of publishing (and resurrecting) a Pano that this
            // method is in the middle of tearing down.
            disabled = true
            enableGeneration++

            pano = mPano
            mPano = null
        }

        // Cancel before tearing down integrations/registrations/Pano below, same reasoning as
        // startTask: shrinks the window for an in-flight /pano command coroutine to call getPano()
        // and hit the `check(!disabled)` there. It's no longer an unhandled crash either way —
        // VelocityCommand attaches a CoroutineExceptionHandler per launch — but there's no reason to
        // let handlers keep running against a Pano this method is about to tear down.
        coroutineScope.cancel()

        // Teardown order: integrations -> registrations -> Pano, mirroring the enable order.
        // Integrations first: they unhook through the still-live Pano instance, and closing Vert.x
        // fires onDisconnect asynchronously after Pano is already gone. No lock held.
        integrationsSnapshot.forEach { runCatching { it.onDisable() } }

        // Commands and the event listener, under registrationLock. Tearing commands down here (not
        // relying on Pano.stop() reaching unregisterCommands()) matters on a reload that never got
        // as far as constructing Pano: `pano` below is null, so Pano.stop() never runs, and without
        // this the commands map would keep its entries while the proxy keeps the registered
        // CommandMeta — a leak per reload. unregisterCommands() is idempotent, so the Pano.stop()
        // path (when Pano did get constructed) is then a harmless no-op here having already run.
        tearDownRegistrations()

        // Outside both locks — see the note above this method.
        pano?.disable()
    }

    // Registration teardown mirrored on enable (register{Commands,EventListeners} are the
    // enable-side counterparts). Only ever mutates fields/collections plus the platform's own
    // (un)register calls under registrationLock — nothing here blocks on Vert.x, a coroutine, or
    // lifecycleLock, so it's safe to call from onDisable() with no other lock held.
    private fun tearDownRegistrations() {
        synchronized(registrationLock) {
            if (commands.isNotEmpty()) {
                val commandManager = server.commandManager

                commands.values.forEach { (_, commandMeta) -> commandManager.unregister(commandMeta) }
                commands.clear()
            }

            // Integrations only remove their own listeners (and BanIntegration doesn't override
            // onDisable() at all — it (un)registers based on server-settings pushes, not lifecycle).
            // Force the teardown here so a reload never leaves the old VelocityEventListener
            // registered with listeners still pointing at the Pano/managers onDisable() is tearing
            // down; eventListenerRegistered=false makes the next onEnable()'s
            // registerEventListeners() build a fresh VelocityEventListener instead of reusing this
            // one.
            if (eventListenerRegistered) {
                server.eventManager.unregisterListener(this, velocityEventListener)
                eventListenerRegistered = false
            }
        }
    }

    override fun registerCommands(commands: List<Command>) {
        synchronized(registrationLock) {
            commands
                .forEach { command ->
                    val commandManager = server.commandManager
                    val velocityCommand = VelocityCommand(command, this)

                    val commandMeta = commandManager.metaBuilder(command.name)
                        .plugin(this)
                        .build()

                    commandManager.register(commandMeta, velocityCommand)

                    this.commands[command] = velocityCommand to commandMeta
                }
        }
    }

    // Idempotent: onDisable()'s tearDownRegistrations() already tears `commands` down (under
    // registrationLock) before Pano is disabled, so by the time Pano.stop() ->
    // CommandManager.disable() reaches this on a normal shutdown, the passed-in commands are already
    // gone from `this.commands` and the loop below is a no-op for each of them. Only ever unregisters
    // the entries this instance itself registered for the passed-in commands -- kept as hygiene over
    // the old `this.commands.forEach {...}; this.commands.clear()` shape, which dropped every command
    // this VelocityMain had ever registered instead of just the ones it was passed.
    //
    // This keying is NOT what makes a lost double-init race safe, though, and never was: Velocity's
    // CommandManager.unregister(CommandMeta) is alias-scoped -- it drops the "pano" node from the
    // shared Brigadier command root no matter which registration produced that CommandMeta, so if two
    // Pano instances had ever coexisted, either one calling this would still tear out the other's live
    // command regardless of which Command object keyed which CommandMeta here. That hazard is gone
    // because getPano() (see there) no longer lets a second Pano instance be constructed in the first
    // place, not because of anything this map's keying does.
    override fun unregisterCommands(commands: List<Command>) {
        synchronized(registrationLock) {
            commands.forEach { command ->
                this.commands.remove(command)?.let { (_, commandMeta) ->
                    server.commandManager.unregister(commandMeta)
                }
            }
        }
    }

    override fun getServerData(): ServerData = serverData

    override fun getPluginClassLoader(): java.net.URLClassLoader =
        VelocityMain::class.java.classLoader as java.net.URLClassLoader

    override fun translateColor(text: String): String = LegacyColorConverter.translate(text)

    // registrationLock (R3): without this, an unsynchronized check-then-act here races onDisable()'s
    // tearDownRegistrations() tearing eventListenerRegistered/velocityEventListener down
    // concurrently — a reload landing mid-registration can leave the new listener registered after
    // disable, or unregister one the caller still believes is live. Non-deadlocking against
    // onDisable(): onDisable() never holds registrationLock across pano.disable() (see the class and
    // onDisable() notes), so by the time closeVertx() blocks the proxy thread, registrationLock is
    // free — including for EventManager.disable()'s call into unregisterEventListeners() from the
    // Vert.x event loop during that same close.
    override fun registerEventListeners(listeners: Set<Listener>) {
        synchronized(registrationLock) {
            if (eventListenerRegistered) {
                velocityEventListener.listeners.addAll(listeners)

                return
            }

            // CopyOnWriteArraySet: listeners is read from platform threads (join/disconnect/pre-login)
            // while registerEventListeners/unregisterEventListeners mutate it from the Vert.x event loop.
            velocityEventListener = VelocityEventListener(this, CopyOnWriteArraySet(listeners))

            server.eventManager.register(this, velocityEventListener)

            eventListenerRegistered = true
        }
    }

    // registrationLock: see registerEventListeners() above — same lock, same reasoning.
    override fun unregisterEventListeners(listeners: Set<Listener>) {
        synchronized(registrationLock) {
            if (!eventListenerRegistered) {
                return
            }

            // Partial removal only: mirrors Spigot/Bungee. Unregistering the whole
            // VelocityEventListener here would tear down every other integration's listeners too.
            velocityEventListener.listeners.removeAll(listeners)

            if (velocityEventListener.listeners.isNotEmpty()) {
                return
            }

            // Only our own listener object: unregisterListeners(plugin) would also drop VelocityMain's
            // @Subscribe methods, which Velocity auto-registers, so ProxyReloadEvent/ProxyShutdownEvent
            // would never fire again after the first disable.
            server.eventManager.unregisterListener(this, velocityEventListener)

            eventListenerRegistered = false
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
        val optionalPlayer = server.getPlayer(player)

        if (optionalPlayer.isPresent) {
            // translateColor() emits console ANSI escapes, not something Component.text() can
            // render for a player — deserialize the raw '&'-coded string into a real Component.
            optionalPlayer.get().disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize(message))
        }
    }
}