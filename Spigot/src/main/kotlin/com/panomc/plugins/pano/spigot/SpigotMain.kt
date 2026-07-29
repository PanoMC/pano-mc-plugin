package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.Integration
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.integration.BanIntegration
import com.panomc.plugins.pano.core.integration.PermissionIntegration
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import com.panomc.plugins.pano.spigot.integration.AuthMeIntegration
import io.vertx.core.http.WebSocket
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandMap
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.net.URL
import java.net.URLClassLoader
import java.util.*
import java.util.concurrent.CopyOnWriteArraySet
import java.util.function.Consumer
import java.util.jar.Manifest
import java.util.logging.Logger

class SpigotMain : JavaPlugin(), PanoPluginMain {
    // Two distinct monitors so no lock is ever held across the blocking Pano.init()/Pano.disable()
    // calls, both of which re-enter this class from the Vert.x event loop (registerCommands,
    // registerEventListeners, unregisterCommands, unregisterSchedules, unregisterEventListeners —
    // see CommandManager/ScheduleManager/EventManager). lifecycleLock guards mPano, disabled and
    // integrations; registrationLock guards commands/scheduledTasks/spigotEventListener. Never
    // `this` as the monitor: getPano() used to be `@Synchronized` (i.e. `this`), which was safe
    // only because nothing else happened to synchronize on `this` — named locks make that
    // boundary explicit instead of implicit.
    private val lifecycleLock = Any()
    private val registrationLock = Any()

    // Not a `by lazy`: onDisable() calls mPano.disable(), which closes the shared static Vert.x.
    // A runtime re-enable of this same plugin instance (e.g. PlugMan disabling and re-enabling
    // without recreating the classloader) must build a fresh Pano instead of reusing the dead
    // one — a `by lazy` memoizes permanently, so onStart() would call onServerStart() on a
    // verticle whose Vert.x is already closed and PlatformManager would never reconnect. Mirrors
    // VelocityMain/FabricMain. Guarded by lifecycleLock; the lock is always released before the
    // blocking Pano.init()/Pano.disable() call itself (see getPano()/onDisable()).
    private var mPano: Pano? = null

    // Guards getPano() against resurrecting a Pano after onDisable() has torn one down: flipped to
    // true inside onDisable()'s lifecycleLock block together with the mPano capture, and cleared at
    // the top of the following onEnable(). Checked in getPano()'s fast-path critical section, under
    // lifecycleLock. Mirrors VelocityMain.disabled.
    @Volatile
    private var disabled = false

    // Guarded by registrationLock.
    private val commands = mutableListOf<SpigotCommand>()

    // Guarded by registrationLock.
    private val scheduledTasks = mutableMapOf<() -> Unit, Any>()
    private val serverData by lazy { SpigotServerData(this) }
    private val mPanoLogger by lazy { getPanoLogger() }
    internal val eventHelper by lazy { SpigotEventHelper(this) }
    // Nullable var, not lateinit: onDisable() clears this to null so a following onEnable() ->
    // registerEventListeners() builds and registers a fresh SpigotEventListener instead of
    // silently reusing the old one. Bukkit's plugin disable already auto-unregisters the old
    // listener from HandlerList (SimplePluginManager.disablePlugin() calls
    // HandlerList.unregisterAll(plugin)), so without this reset a re-enable would keep mutating an
    // object no longer wired into Bukkit's event dispatch — pre-login ban checks would silently
    // stop firing after a runtime disable/re-enable of this same plugin instance (e.g. PlugMan).
    // Guarded by registrationLock — every read and write (registerEventListeners,
    // unregisterEventListeners, onDisable) now goes through registrationLock, so the lock itself
    // supplies the happens-before edge between the Vert.x event loop (registerEventListeners) and
    // the Bukkit main thread (onDisable() -> PermissionIntegration.onDisable() ->
    // unregisterEventListeners()'s null guard). @Volatile kept as a belt-and-suspenders guard on
    // top of that. Mirrors FabricMain.fabricEventListener/VelocityMain.velocityEventListener.
    @Volatile
    internal var spigotEventListener: SpigotEventListener? = null

    // Long-lived scope for command handlers (see SpigotCommand): created in onEnable, cancelled
    // in onDisable, so a /pano coroutine can't outlive the plugin and touch a torn-down Pano.
    // Mirrors BungeeMain/VelocityMain/FabricMain. @Volatile: reassigned per enable/re-enable on the
    // Bukkit main thread but read from arbitrary command-dispatch threads via SpigotCommand, which
    // is not otherwise synchronized against this field — without @Volatile a command thread could
    // observe a stale, already-cancelled scope from a previous enable cycle.
    @Volatile
    internal lateinit var coroutineScope: CoroutineScope
        private set

    // Not a `by lazy`: a `by lazy` memoizes permanently, so after a runtime re-enable of this same
    // plugin instance these would keep the PermissionIntegration/BanIntegration/AuthMeIntegration
    // built for the torn-down enable cycle — their `pano`/`platformManager` lazies still point at
    // the old, disabled Pano (closed Vert.x). Rebuilt in onEnable() instead, same lifecycle as
    // mPano. Both writes (onEnable()/onDisable()) go under lifecycleLock, matching mPano/disabled —
    // see the class comment. Also @Volatile: read lock-free from the Vert.x event loop via
    // onConnectionEstablished/onDisconnect/onServerSettingsChanged/onPermissionsSnapshotUpdated
    // below, so a write under the lock must still be visible there without the reader taking it.
    // Mirrors VelocityMain.integrations.
    @Volatile
    private var integrations: List<Integration> = emptyList()

    override fun onEnable() {
        // Cleared before anything else so a getPano() call racing this onEnable() (e.g. a lingering
        // scheduler task from a previous enable cycle) sees the plugin as enabled again rather than
        // tripping the check(!disabled) fast-path guard below.
        disabled = false

        coroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
                mPanoLogger.warning("Unhandled coroutine exception: ${throwable.message}")
            }
        )

        synchronized(lifecycleLock) {
            integrations = listOf(
                PermissionIntegration(this),
                BanIntegration(this),
                AuthMeIntegration(this),
            )
        }

        if (SpigotServerUtil.isFolia()) {
            try {
                val scheduler = server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server)
                val runDelayed = scheduler.javaClass.getMethod(
                    "runDelayed",
                    Plugin::class.java,
                    Consumer::class.java,
                    Long::class.javaPrimitiveType
                )
                val task = Consumer<Any> {
                    onStart()
                }
                runDelayed.invoke(scheduler, this, task, 1L)
            } catch (exception: Exception) {
                mPanoLogger.severe("Failed to schedule start task: ${exception.message}")
            }
        } else {
            server.scheduler.scheduleSyncDelayedTask(this) {
                onStart()
            }
        }
    }

    private fun onStart() {
        // Each integration gets its own try/catch, mirroring the onDisable() loop below: without
        // this, AuthMeIntegration.onEnable() reaching platformManager -> getPano() -> Pano.init()
        // and Pano.init() propagating a config-migration failure would escape onStart() entirely,
        // skipping getPano().onServerStart() below and surfacing as a raw scheduler stack trace
        // instead of the "Failed to start Pano" message that try/catch is meant to produce.
        integrations.forEach {
            try {
                it.onEnable()
            } catch (exception: Exception) {
                mPanoLogger.severe("Failed to enable integration ${it.javaClass.simpleName}: ${exception.message}")
            }
        }

        // getPano() constructs Pano as soon as it's called below, before onServerStart() runs, so
        // a thrown exception (PlatformManager.start() does retrying network work) still leaves
        // mPano non-null for onDisable() to clean up.
        try {
            getPano().onServerStart()
        } catch (exception: Exception) {
            mPanoLogger.severe("Failed to start Pano: ${exception.message}")
        }
    }

    override fun onDisable() {
        // Integrations first: they may need to schedule Bukkit tasks while the plugin is still
        // enabled. Calling mPano.disable() first closes Vert.x asynchronously; the WebSocket
        // onDisconnect can fire after the plugin is already disabled, which would break
        // AuthMeIntegration's reload (runTask on a disabled plugin).
        // Each integration gets its own try/catch: onStart() may never have run (aborted startup),
        // so PermissionIntegration.onDisable() can hit unregisterEventListeners's null guard or
        // similar not-yet-initialized state — that must not skip BanIntegration/AuthMeIntegration.
        integrations.forEach {
            try {
                it.onDisable()
            } catch (exception: Exception) {
                mPanoLogger.severe("Failed to disable integration ${it.javaClass.simpleName}: ${exception.message}")
            }
        }
        // Release so a following onEnable() builds fresh integrations instead of reusing ones
        // wired to this (now torn-down) Pano — see the field's declaration above. lifecycleLock
        // only: pure field assignment, no blocking call inside.
        synchronized(lifecycleLock) {
            integrations = emptyList()
        }

        // Cleared (not left for the next registerEventListeners() call to reuse) so a following
        // onEnable() always builds and registers a fresh SpigotEventListener — see the field's
        // declaration above for why reusing the old one would silently break ban checks after a
        // re-enable. Must run after the integrations.onDisable() loop above, which still needs to
        // read this to unregister PermissionIntegration's listener. registrationLock only — never
        // nested inside lifecycleLock, and released well before the blocking pano?.disable() call
        // below.
        synchronized(registrationLock) {
            spigotEventListener = null
        }

        // mPano is only non-null once onStart() actually reached getPano(); null-safe so a
        // disable-before-start never constructs Pano just to tear it down. The reference is
        // captured and cleared under lifecycleLock, then the lock is released *before* the
        // blocking disable() call — see R2/getPano(). Clearing mPano here (rather than in a
        // finally after disable()) still guarantees a following onEnable()/onStart() builds a
        // fresh Pano even if disable() throws, since mPano is already null by the time disable()
        // runs. disabled is flipped true in the same block so any getPano() call that reaches the
        // fast-path check afterwards (this thread has already released registrationLock and the
        // integrations/spigotEventListener teardown above) sees the plugin as disabled instead of
        // constructing a Pano/Vert.x that nothing will ever dispose of.
        val pano = synchronized(lifecycleLock) {
            val p = mPano
            mPano = null
            disabled = true
            p
        }
        pano?.disable()

        // Guard isInitialized: onDisable() can be reached even if onEnable() never finished
        // assigning it (e.g. it threw before this line).
        if (::coroutineScope.isInitialized) {
            coroutineScope.cancel()
        }
    }

    override fun registerCommands(commands: List<Command>) {
        // registrationLock only: pure field mutation plus Bukkit's own CommandMap/PluginCommand
        // API, no Vert.x/coroutine/lifecycleLock call inside. syncRegisteredCommands() stays
        // outside the lock — it only reflects on the server/online players, it never touches
        // `this.commands`.
        synchronized(registrationLock) {
            val commandMap = getCommandMap()

            commands
                .map { SpigotCommand(it, this) }
                .forEach { command ->
                    this.commands.add(command)

                    val pluginCommand = getCommand(command.name.lowercase())
                    if (pluginCommand != null) {
                        pluginCommand.description = command.description
                        pluginCommand.usage = command.usage
                        pluginCommand.permission = command.permission
                        pluginCommand.permissionMessage = command.permissionMessage
                        pluginCommand.setExecutor(command)
                    } else {
                        commandMap.register(name, command)
                    }
                }
        }

        syncRegisteredCommands()
    }

    override fun unregisterCommands(commands: List<Command>) {
        synchronized(registrationLock) {
            val commandMap = getCommandMap()

            this.commands
                .forEach { command ->
                    command.unregister(commandMap)
                }

            this.commands.clear()
        }

        syncRegisteredCommands()
    }

    private fun getCommandMap(): CommandMap {
        val commandMapField = Bukkit.getServer().javaClass.getDeclaredField("commandMap")

        commandMapField.isAccessible = true

        return commandMapField.get(Bukkit.getServer()) as CommandMap
    }

    private fun syncRegisteredCommands() {
        try {
            server.javaClass.getMethod("syncCommands").invoke(server)
        } catch (_: NoSuchMethodException) {
            // 1.8 has no client command tree to sync.
        } catch (exception: Exception) {
            mPanoLogger.warning("Failed to sync server commands: ${exception.message}")
        }

        server.onlinePlayers.forEach { player ->
            try {
                player.javaClass.getMethod("updateCommands").invoke(player)
            } catch (_: NoSuchMethodException) {
                // Older Bukkit players do not expose updateCommands.
            } catch (exception: Exception) {
                mPanoLogger.warning("Failed to sync commands for ${player.name}: ${exception.message}")
            }
        }
    }

    override fun registerSchedule(task: () -> Unit) {
        // registrationLock only: field mutation plus Bukkit/Folia's own scheduler API, no blocking
        // call inside. The nested stopSchedule(task) call below re-enters the same registrationLock
        // on the same thread, which the JVM's intrinsic monitor allows (reentrant), so this does
        // not self-deadlock.
        synchronized(registrationLock) {
            if (scheduledTasks.containsKey(task)) {
                stopSchedule(task)
            }

            if (SpigotServerUtil.isFolia()) {
                try {
                    val scheduler = server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server)
                    val runAtFixedRate = scheduler.javaClass.getMethod(
                        "runAtFixedRate",
                        Plugin::class.java,
                        Consumer::class.java,
                        Long::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType
                    )
                    val consumer = Consumer<Any> { _ -> task() }
                    val scheduled = runAtFixedRate.invoke(scheduler, this, consumer, 1L, 20L)
                    scheduledTasks[task] = scheduled
                } catch (exception: Exception) {
                    mPanoLogger.severe("Failed to schedule task: ${exception.message}")
                }
            } else {
                scheduledTasks[task] = server.scheduler.scheduleSyncRepeatingTask(this, task, 1, 20)
            }
        }
    }

    override fun stopSchedule(task: () -> Unit) {
        synchronized(registrationLock) {
            scheduledTasks[task]?.let { scheduled ->
                if (SpigotServerUtil.isFolia()) {
                    try {
                        scheduled.javaClass.getMethod("cancel").invoke(scheduled)
                    } catch (exception: Exception) {
                        mPanoLogger.severe("Failed to cancel task: ${exception.message}")
                    }
                } else {
                    server.scheduler.cancelTask(scheduled as Int)
                }
            }
            scheduledTasks.remove(task)
        }
    }

    override fun unregisterSchedules(tasks: List<() -> Unit>) {
        // No wrapping lock here: each stopSchedule(task) call already takes/releases
        // registrationLock for its own task, which is enough for consistency of the
        // scheduledTasks map — matches the pre-existing per-task structure.
        tasks.forEach { task ->
            stopSchedule(task)
        }
    }

    override fun getServerData(): ServerData = serverData

    override fun getPluginClassLoader(): URLClassLoader {
        val pluginClassLoader = javaClass.classLoader
        if (pluginClassLoader is URLClassLoader) {
            return pluginClassLoader
        }

        val panoManifestUrl = findPanoManifestUrl(pluginClassLoader)
        return object : URLClassLoader(emptyArray(), pluginClassLoader) {
            override fun findResource(name: String?): URL? {
                if (name == "META-INF/MANIFEST.MF" && panoManifestUrl != null) {
                    return panoManifestUrl
                }
                return pluginClassLoader.getResource(name)
            }

            override fun findResources(name: String?): Enumeration<URL> = pluginClassLoader.getResources(name)
        }
    }

    private fun findPanoManifestUrl(pluginClassLoader: ClassLoader): URL? {
        return try {
            val manifestUrls = pluginClassLoader.getResources("META-INF/MANIFEST.MF")
            while (manifestUrls.hasMoreElements()) {
                val url = manifestUrls.nextElement()
                try {
                    url.openStream().use { stream ->
                        val manifest = Manifest(stream)
                        if (manifest.mainAttributes.getValue("VERSION") != null &&
                            manifest.mainAttributes.getValue("BUILD_TYPE") != null
                        ) {
                            return url
                        }
                    }
                } catch (_: Exception) {
                    // Skip unreadable manifests from shaded dependencies.
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun translateColor(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

    override fun registerEventListeners(listeners: Set<Listener>) {
        // registrationLock only: field mutation plus Bukkit's own HandlerList registration, no
        // blocking call inside.
        synchronized(registrationLock) {
            val existingListener = spigotEventListener
            if (existingListener != null) {
                existingListener.listeners.addAll(listeners)
                return
            }

            // CopyOnWriteArraySet: registerEventListeners/unregisterEventListeners mutate this set
            // from the Vert.x event loop while Bukkit reads it from the main thread and the async
            // login thread (platform-modules-9) — a plain LinkedHashSet is neither thread-safe nor
            // visible across those threads.
            val newListener = SpigotEventListener(this, eventHelper, CopyOnWriteArraySet(listeners))
            spigotEventListener = newListener

            server.pluginManager.registerEvents(newListener, this)
        }
    }

    override fun unregisterEventListeners(listeners: Set<Listener>) {
        // spigotEventListener is only assigned once onStart() runs (or null again after
        // onDisable()); onDisable() can reach here (via PermissionIntegration.onDisable()) even
        // when onStart() never fired. registrationLock only, matching registerEventListeners above.
        synchronized(registrationLock) {
            val existingListener = spigotEventListener ?: return

            existingListener.listeners.removeAll(listeners)
        }
    }

    override fun getPanoLogger(): Logger = ColoredLogger("[Pano] ")

    override fun getPano(): Pano {
        // Fast path: an existing Pano, under lifecycleLock only. check(!disabled) closes the gap
        // where onDisable() has already captured-and-nulled mPano (and called pano.disable()) but
        // something still reachable after that — an integration's `pano by lazy`, a late scheduler
        // task, an in-flight command coroutine — calls getPano() again: without this it would fall
        // through to Pano.init() below and build a brand-new Pano/Vert.x that nothing will ever
        // dispose of. Matches VelocityMain.
        synchronized(lifecycleLock) {
            mPano?.let { return it }
            check(!disabled) { "Pano requested after SpigotMain was disabled" }
        }

        // Pano.init() is a blocking call (runBlocking { vertx.deployVerticle(...).coAwait() }) that
        // re-enters this class from the Vert.x event loop (CommandManager.init() ->
        // registerCommands(), EventManager.init() -> registerEventListeners(), ...), both of which
        // take registrationLock. lifecycleLock must therefore be released before this call — R2.
        val created = Pano.init(this)

        // Racing double-init: two threads can both observe mPano == null above and both reach here
        // concurrently (no lock was held in between). Re-check under lifecycleLock and let the
        // loser tear down only its own verticle via Pano.dispose() — NOT disable(), which would
        // close the shared companion-object Vert.x singleton every Pano (including the winner) is
        // deployed onto. dispose() is fire-and-forget (kicks off vertx.undeploy() and returns; no
        // coAwait/runBlocking) and never touches vertxInstance, so — unlike disable() — it would be
        // safe to call even while still holding lifecycleLock; it's kept outside the block anyway
        // for consistency with the rest of this method.
        val winner = synchronized(lifecycleLock) {
            val existing = mPano
            if (existing != null) {
                existing
            } else {
                mPano = created
                created
            }
        }

        if (winner !== created) {
            created.dispose()
        }

        return winner
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

    override fun onPermissionsSnapshotUpdated(message: com.panomc.plugins.pano.core.platform.message.response.PermissionsSnapshotUpdatedMessage) {
        integrations.forEach { it.onPermissionsSnapshotUpdated(message) }
    }

    override fun kickPlayer(player: String, message: String) {
        // PanoPluginMain.kickPlayer's contract is raw '&' in, rendered out — player.kickPlayer()
        // (via SpigotServerUtil.kickPlayer) does no conversion of its own, unlike the other
        // platforms' kick calls.
        SpigotServerUtil.kickPlayer(this, server.getPlayer(player) ?: return, translateColor(message))
    }

    /**
     * Matches the server’s own never-joined / offline name→UUID mapping (version-accurate).
     */
    override fun getNeverJoinedPlayerUniqueId(minecraftName: String): UUID {
        return Bukkit.getOfflinePlayer(minecraftName).uniqueId
    }
}