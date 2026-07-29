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
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class VelocityMain : PanoPluginMain {
    // Two distinct monitors so that nothing reachable from Pano.init()/Pano.disable()'s callbacks
    // into this class can ever block on a lock a thread is holding while parked inside those two
    // (blocking, event-loop-re-entrant) calls. Neither lock is ever held across either call — see
    // getPano()/onDisable() below.
    //   lifecycleLock    guards: mPano, integrations, disabled, startTask
    //   registrationLock guards: commands, scheduledTasks, velocityEventListener + eventListenerRegistered
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

    // Guarded by registrationLock.
    private val commands = mutableMapOf<VelocityCommand, CommandMeta>()
    private val scheduledTasks = mutableMapOf<() -> Unit, ScheduledTask>()
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

    // Not @Synchronized as a whole — see the lifecycleLock/registrationLock note on the class.
    // Pano.init() below does `runBlocking { vertx.deployVerticle(pano).coAwait() }`: it blocks this
    // thread while the deployment runs on the Vert.x event loop and calls back into this class
    // (CommandManager.init() -> registerCommands(), EventManager.init() -> registerEventListeners(),
    // both registrationLock). If this thread held any lock across that call, and the event loop's
    // callback needed the same lock, that would be a guaranteed deadlock — so lifecycleLock is only
    // ever held for the plain field reads/writes below, never across Pano.init() itself.
    override fun getPano(): Pano {
        synchronized(lifecycleLock) {
            mPano?.let { return it }
            check(!disabled) { "Pano requested after VelocityMain was disabled" }
        }

        val created = Pano.init(this)

        var staleToDispose: Pano? = null
        var result: Pano? = null
        var raceLostToDisable = false

        synchronized(lifecycleLock) {
            val existing = mPano

            when {
                existing != null -> {
                    // Another getPano() call won the race while this thread was blocked in
                    // Pano.init() above.
                    staleToDispose = created
                    result = existing
                }

                disabled -> {
                    // onDisable() ran while this thread was blocked in Pano.init() above; don't
                    // resurrect a Pano onto a VelocityMain instance that has since been torn down.
                    staleToDispose = created
                    raceLostToDisable = true
                }

                else -> {
                    mPano = created
                    result = created
                }
            }
        }

        // dispose() (unlike disable()) is fire-and-forget and never blocks, but it's still called
        // outside lifecycleLock here: it's the loser's own verticle deployment, so there's nothing
        // gained by holding the lock across it, and keeping the shape identical to Pano.init() above
        // avoids a lock being added back here by mistake later.
        staleToDispose?.dispose()

        if (raceLostToDisable) {
            check(false) { "Pano requested after VelocityMain was disabled" }
        }

        return result!!
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
            integrations = newIntegrations
        }

        newIntegrations.forEach { it.onEnable() }

        val task: () -> Unit = {
            // A reload/shutdown can land between scheduling and this task actually firing (or while
            // it is blocked entering getPano()'s lifecycleLock behind onDisable()); check the flag
            // so a stale task doesn't resurrect a Pano that was just torn down. Unlocked read of the
            // @Volatile flag is only an optimization — getPano() re-checks under lifecycleLock.
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

    // Not @Synchronized as a whole, and no single lock is ever held across pano?.disable() below —
    // see the lifecycleLock/registrationLock note on the class. pano.disable() runs
    // Pano.closeVertx(), which does `runBlocking { vertx.close().coAwait() }`: it blocks this thread
    // until Vert.x finishes undeploying the Pano verticle, and undeploy runs Pano.stop() on the
    // Vert.x event loop, which calls back into this class — CommandManager.disable() ->
    // unregisterCommands(), EventManager.disable() -> unregisterEventListeners() (both
    // registrationLock), ScheduleManager.disable() -> unregisterSchedules()/stopSchedule()
    // (registrationLock), PlatformManager.stop() -> onDisconnect() -> Integration.onDisconnect()
    // (no lock, R4). If this thread held registrationLock (or lifecycleLock) while blocked in
    // runBlocking, those event-loop calls would block forever on a lock only this (blocked) thread
    // can release — a guaranteed deadlock. So state is captured into locals under each lock and both
    // locks are fully released before pano.disable() is called.
    private fun onDisable() {
        val pano: Pano?
        val integrationsSnapshot: List<Integration>

        synchronized(lifecycleLock) {
            startTask?.cancel()
            startTask = null

            integrationsSnapshot = integrations
            integrations = emptyList()

            // Flip after capturing integrations above (whose onDisable() below still needs a live
            // Pano/managers) but before mPano is released, so any getPano() call still blocked on
            // lifecycleLock — e.g. a boot task from a previous onEnable that is mid-flight — sees the
            // disabled state instead of reconstructing a Pano that this method is in the middle of
            // tearing down.
            disabled = true

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

    // Registration teardown mirrored on enable (register{Commands,EventListeners} +
    // registerSchedule are the enable-side counterparts). Only ever mutates fields/collections plus
    // the platform's own (un)register calls under registrationLock (R3) — nothing here blocks on
    // Vert.x, a coroutine, or lifecycleLock, so it's safe to call from onDisable() with no other
    // lock held.
    private fun tearDownRegistrations() {
        synchronized(registrationLock) {
            if (commands.isNotEmpty()) {
                val commandManager = server.commandManager

                commands.values.forEach { commandManager.unregister(it) }
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

                    this.commands[velocityCommand] = commandMeta
                }
        }
    }

    // Idempotent: onDisable() already tears `commands` down (under registrationLock) before Pano is
    // disabled, so by the time Pano.stop() -> CommandManager.disable() reaches this on a normal
    // shutdown, `this.commands` is already empty and both the forEach and clear() below are no-ops.
    override fun unregisterCommands(commands: List<Command>) {
        synchronized(registrationLock) {
            this.commands
                .forEach { command ->
                    val commandManager = server.commandManager

                    commandManager.unregister(command.value)
                }

            this.commands.clear()
        }
    }

    override fun registerSchedule(task: () -> Unit) {
        synchronized(registrationLock) {
            scheduledTasks[task]?.cancel()
            scheduledTasks.remove(task)

            scheduledTasks[task] = server.scheduler
                .buildTask(this, task)
                .repeat(1L, TimeUnit.SECONDS)
                .schedule()
        }
    }

    override fun stopSchedule(task: () -> Unit) {
        synchronized(registrationLock) {
            scheduledTasks[task]?.cancel()
            scheduledTasks.remove(task)
        }
    }

    override fun unregisterSchedules(tasks: List<() -> Unit>) {
        tasks.forEach { task ->
            stopSchedule(task)
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
        val optionalPlayer = server.getPlayer(player)

        if (optionalPlayer.isPresent) {
            // translateColor() emits console ANSI escapes, not something Component.text() can
            // render for a player — deserialize the raw '&'-coded string into a real Component.
            optionalPlayer.get().disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize(message))
        }
    }
}