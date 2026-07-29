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
    // Two distinct monitors, deliberately never `this`: Pano.init()/Pano.disable() are blocking
    // calls (runBlocking { deployVerticle/vertx.close ... }) that re-enter this class from the
    // Vert.x event loop while the calling thread is parked inside them (CommandManager.init() ->
    // registerCommands(), EventManager.init() -> registerEventListeners() during init();
    // CommandManager.disable()/ScheduleManager.disable()/EventManager.disable()/
    // PlatformManager.stop()->onDisconnect() during disable()). lifecycleLock guards the
    // construct/teardown bookkeeping (mPano, integrations, disabled, startTask) and is NEVER held
    // across those blocking calls; registrationLock guards the command/schedule/listener
    // registrations those callbacks touch and never needs to wait on Vert.x, a coroutine, or
    // lifecycleLock, so it can be held for the whole (fast, local) duration of each callback. As
    // long as that split holds, neither lock can ever be the thing an event-loop callback is
    // blocked on while its own thread is the one Pano.init()/disable() is waiting for.
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
    // getPano() refuse to resurrect a Pano — and dispose of one it already built via Pano.init()
    // outside the lock — if onDisable() finished while that construction was in flight.
    private var disabled = false

    // Guarded by lifecycleLock. The one-off onServerStart() boot task: cancelled in onDisable() so
    // a shutdown landing mid-boot doesn't leave it free to call getPano() after teardown started.
    private var startTask: ScheduledTask? = null

    // Guarded by registrationLock.
    private val scheduledTasks = mutableMapOf<() -> Unit, ScheduledTask>()
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

    // No lock may be held across mPano.disable() below (it runs Pano.closeVertx() ->
    // runBlocking { vertx.close().coAwait() }, blocking this thread until the event loop finishes
    // undeploying) or across pano?.disable() in general, since that undeploy calls back into this
    // class from the Vert.x event loop — see the class-level comment. So this method only ever
    // takes a lock for a bounded, local mutation, and always releases it before making a blocking
    // call: lifecycleLock to capture-and-clear mPano/integrations/startTask/disabled, then
    // registrationLock (via the block below) to actually unregister the event listener, then no
    // lock at all for the integration callbacks and pano.disable() itself.
    override fun onDisable() {
        val pano: Pano?
        val integrationsToDisable: List<Integration>

        synchronized(lifecycleLock) {
            // Cancel the deferred start task first: if it never ran, mPano must stay unconstructed
            // (see the null check below); if it's mid-flight inside getPano(), disabled=true here
            // makes it dispose of whatever Pano it builds instead of resurrecting one after this
            // method has already finished tearing down (see getPano()).
            startTask?.cancel()
            startTask = null
            disabled = true

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
            commands
                .map { BungeeCommand(it, this) }
                .forEach { command ->
                    proxy.pluginManager.registerCommand(this, command)
                }
        }
    }

    override fun unregisterCommands(commands: List<Command>) {
        synchronized(registrationLock) {
            proxy.pluginManager.unregisterCommands(this)
        }
    }

    override fun registerSchedule(task: () -> Unit) {
        synchronized(registrationLock) {
            if (scheduledTasks.containsKey(task)) {
                stopSchedule(task)
            }

            // A genuine repeating task, not a one-shot that re-invokes registerSchedule(task) from
            // inside the callback: the old self-reschedule left a window where a scheduler-pool
            // thread sitting between task.invoke() and the re-registration call could block on
            // registrationLock while ScheduleManager.disable() -> unregisterSchedules() ran the
            // cancel/remove on the Vert.x event loop, then acquire the lock and resurrect the
            // just-cancelled entry. BungeeCord's TaskScheduler.schedule(plugin, task, delay, period,
            // unit) overload runs the same Runnable on a fixed period on its own, so cancel() here
            // (via stopSchedule/unregisterSchedules) is the only thing that can ever remove or
            // re-add an entry, matching Velocity/Spigot/Fabric's shape.
            scheduledTasks[task] = proxy.scheduler.schedule(this, Runnable { task.invoke() }, 1, 1, TimeUnit.SECONDS)
        }
    }

    override fun stopSchedule(task: () -> Unit) {
        synchronized(registrationLock) {
            scheduledTasks[task]?.cancel()
            scheduledTasks.remove(task)
        }
    }

    override fun unregisterSchedules(tasks: List<() -> Unit>) {
        synchronized(registrationLock) {
            tasks.forEach { task ->
                stopSchedule(task)
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
        synchronized(lifecycleLock) {
            mPano?.let { return it }
            check(!disabled) { "Pano requested after BungeeMain was disabled" }
        }

        // NO lock held across this: Pano.init() blocks in runBlocking { deployVerticle(...) }, and
        // the verticle's start() calls back into this class (CommandManager.init() ->
        // registerCommands(), EventManager.init() -> registerEventListeners()) from the Vert.x
        // event loop while this thread is parked here. Those callbacks only take registrationLock,
        // never lifecycleLock — but the rule is unconditional (see the class-level comment), so
        // this never holds lifecycleLock here regardless.
        val created = Pano.init(this)

        var staleToDispose: Pano? = null

        val result = synchronized(lifecycleLock) {
            val existing = mPano

            when {
                existing != null -> {
                    // Someone else won the construction race while we were in Pano.init(): drop ours.
                    staleToDispose = created
                    existing
                }

                disabled -> {
                    // onDisable() ran to completion while we were inside Pano.init(): nothing else
                    // will ever dispose of `created` otherwise, leaking its verticle deployment
                    // (WebSocket connection, DI context) past shutdown. dispose() only undeploys
                    // that one deployment, so it can't touch the shared vertxInstance singleton or
                    // reopen the hang this redesign removes even if that singleton is already
                    // closing elsewhere.
                    staleToDispose = created
                    null
                }

                else -> {
                    mPano = created
                    created
                }
            }
        }

        // Outside the lock (not that it would matter now): dispose() only fires off
        // vertx.undeploy(id) and returns, never touching the shared vertxInstance or blocking. Using
        // disable() here would be wrong even off the lock — it would tear down the shared Vert.x
        // that the race's WINNER (mPano) is deployed on, not just this losing instance's deployment.
        staleToDispose?.dispose()

        return result ?: error("Pano requested after BungeeMain was disabled")
    }

    override fun onConnectionEstablished(webSocket: WebSocket?) {
        integrations.forEach { it.onConnectionEstablished(webSocket) }
    }

    override fun onDisconnect() {
        integrations.forEach { it.onDisconnect() }
    }

    override fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {
        integrations.forEach { it.onServerSettingsChanged(serverSettings) }
    }

    override fun onPermissionsSnapshotUpdated(message: PermissionsSnapshotUpdatedMessage) {
        integrations.forEach { it.onPermissionsSnapshotUpdated(message) }
    }

    override fun kickPlayer(player: String, message: String) {
        // disconnect(String) only translates the section sign; callers (e.g. BanPlayerHandler)
        // pass raw '&'-coded messages, so translate here first, mirroring translateColor's other
        // call sites.
        proxy.getPlayer(player)?.disconnect(TextComponent(translateColor(message)))
    }
}
