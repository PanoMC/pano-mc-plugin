package com.panomc.plugins.pano.fabric

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
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class FabricMain : DedicatedServerModInitializer, PanoPluginMain {
    // Two distinct monitors, never `this`: lifecycleLock guards "swap my fields" state (mPano,
    // integrations) and registrationLock guards "run Pano's lifecycle" state (commands,
    // scheduledTasks, fabricEventListener, scheduler). Pano.init()/Pano.disable() are blocking calls that
    // re-enter this class from the Vert.x event loop (CommandManager/EventManager/ScheduleManager
    // callbacks), so neither lock is ever held across those calls — see getPano() and
    // SERVER_STOPPING below. Because the two locks are never nested (no method holds one while
    // acquiring the other) and neither critical section ever blocks on Vert.x/a coroutine, no cycle
    // can form between them or with the event loop.
    private val lifecycleLock = Any()
    private val registrationLock = Any()

    // Not a `by lazy`: SERVER_STOPPING fires unconditionally even when the server never finished
    // starting (port bind failure, world load failure, ...), and forcing Pano/Vert.x init during
    // shutdown NPEs deep inside DI (serverData needs `server`, which is still null) and aborts
    // MinecraftServer.stopServer() entirely (platform-modules-11).
    // Guarded by lifecycleLock.
    @Volatile
    private var mPano: Pano? = null

    // Not a `val`: SERVER_STOPPING shuts this down (shutdownNow()), and a shut-down
    // ScheduledExecutorService rejects every further submission — reusing it across a Fabric
    // integrated-server restart (SERVER_STOPPING -> a fresh SERVER_STARTED without the mod itself
    // reloading) would silently drop every task scheduled after that point. Rebuilt every
    // SERVER_STARTED, nulled after shutdownNow() on SERVER_STOPPING (R7, matches
    // fabricEventListener/serverData/pluginScope). Guarded by registrationLock, like
    // scheduledTasks/registeredCommands below: written on the server thread
    // (SERVER_STARTED/SERVER_STOPPING) and read by registerSchedule/stopSchedule/
    // unregisterSchedules (via ScheduleManager, off the Vert.x event loop) — all of those already
    // take registrationLock, so no @Volatile is needed as long as every access stays inside it.
    private var scheduler: ScheduledExecutorService? = null

    // scheduledTasks/registeredCommands: guarded by registrationLock.
    private val scheduledTasks = mutableMapOf<() -> Unit, ScheduledFuture<*>>()
    private val registeredCommands = mutableListOf<Command>()

    // Volatile: written under registrationLock from the Vert.x event loop
    // (registerEventListeners/unregisterEventListeners, reached via GetServerSettingsHandler on a
    // connection/settings change) and read WITHOUT any lock from the server thread and Netty I/O
    // threads (join/disconnect/pre-login dispatch) — without @Volatile a freshly registered listener
    // is not guaranteed visible to the reading thread (platform-modules-9). The field doubles as its
    // own "is a listener registered" flag (see registerEventListeners()).
    @Volatile
    internal var fabricEventListener: FabricEventListener? = null

    internal fun getFabricEventListenerOrNull(): FabricEventListener? = fabricEventListener

    // Volatile: written on the server thread (SERVER_STARTED/SERVER_STOPPING) and read from Netty
    // I/O threads via getServerOrNull() (FabricPreLoginHandler) — without this a pre-login arriving
    // right after startup can observe a stale null and skip the ban check entirely (fail open).
    @Volatile
    private var server: MinecraftServer? = null

    // Not `by lazy`: `by lazy` memoizes against the FIRST MinecraftServer forever, so after a
    // Fabric integrated-server restart (SERVER_STOPPING -> a fresh SERVER_STARTED without the mod
    // itself reloading) getServerData() would keep reporting the torn-down server's motd/port/
    // player counts. Rebuilt every SERVER_STARTED from that lambda's own `server` parameter,
    // nulled on SERVER_STOPPING (R7, matches fabricEventListener/scheduler/pluginScope). Volatile
    // for the same cross-thread-visibility reason as `server` above: SpringConfig reads
    // getServerData() while building the PlatformManager bean, which runs on the Vert.x event
    // loop (Pano.init()'s deployVerticle -> start()), not the server thread that sets this.
    @Volatile
    private var serverData: FabricServerData? = null

    internal fun getServerOrNull(): MinecraftServer? = server

    // Long-lived replacement for the per-invocation `CoroutineScope(Dispatchers.IO)` FabricCommand
    // and FabricPreLoginHandler used to create: those were never cancelled on disable and had no
    // exception handler. Created alongside `server` on SERVER_STARTED, cancelled alongside it on
    // SERVER_STOPPING. Volatile for the same cross-thread-visibility reason as `server` above.
    @Volatile
    private var pluginScope: CoroutineScope? = null

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.warning("Unhandled coroutine exception: ${throwable.message}")
    }

    internal fun getPluginScopeOrNull(): CoroutineScope? = pluginScope

    // Bridge JUL → SLF4J so logs use Fabric's native format
    private val logger: Logger = FabricLogger("Pano")

    // Not `by lazy`: Fabric can restart the integrated server (SERVER_STOPPING then a fresh
    // SERVER_STARTED) without the mod itself reloading, so reusing the first-built integrations
    // would leave them wired to a torn-down Pano (their `pano by lazy { main.getPano() }` would
    // keep resolving to the very first Pano instance forever). Rebuilt every SERVER_STARTED (R7).
    // Volatile: written on the server thread (SERVER_STARTED/SERVER_STOPPING), read WITHOUT any
    // lock from the Vert.x event loop (onConnectionEstablished/onDisconnect/onServerSettingsChanged/
    // onPermissionsSnapshotUpdated below) — visibility comes from @Volatile alone, matching
    // fabricEventListener/server/pluginScope above.
    @Volatile
    private var integrations: List<Integration> = emptyList()

    override fun onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            this.server = server
            serverData = FabricServerData(server)
            pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)

            synchronized(registrationLock) {
                scheduler = Executors.newSingleThreadScheduledExecutor { r ->
                    Thread(r, "pano-scheduler").apply { isDaemon = true }
                }
            }

            FabricPreLoginHandler.fabricMain = this

            integrations = listOf(
                PermissionIntegration(this),
                BanIntegration(this),
            )

            integrations.forEach { it.onEnable() }

            getPano().onServerStart()
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            // Integrations before Pano (R5): PermissionIntegration.onDisable() -> stop() closes its
            // LuckPerms EventSubscriptions and calls unregisterEventListeners() while Vert.x/Pano is
            // still up. Running this after mPano?.disable() (as this used to) tears LuckPerms down
            // against an already-closed platform and unregisters listeners after EventManager.disable()
            // already ran. Each step keeps its own try/catch so one failure can't abort the rest of
            // MinecraftServer.stopServer().
            integrations.forEach {
                try {
                    it.onDisable()
                } catch (e: Exception) {
                    logger.warning("Error while disabling a Pano integration: ${e.message}")
                }
            }
            integrations = emptyList()

            // mPano is only non-null once SERVER_STARTED actually ran; skip entirely rather than
            // constructing Pano during shutdown (platform-modules-11). Capture-and-null under
            // lifecycleLock, then release it before the blocking pano.disable() call (R2): disable()
            // runs `runBlocking { vertx.close().coAwait() }` on this thread, and the close re-enters
            // this class from the Vert.x event loop (unregisterCommands/unregisterSchedules/
            // unregisterEventListeners), each of which only ever takes registrationLock — but holding
            // lifecycleLock here regardless would still be wrong per R2, so it's released first.
            val pano = synchronized(lifecycleLock) {
                val p = mPano
                mPano = null
                p
            }

            try {
                pano?.disable()
            } catch (e: Exception) {
                logger.warning("Error while disabling Pano: ${e.message}")
            }

            // Shut down and drop the reference under registrationLock (matching every other write
            // to `scheduler`) so SERVER_STARTED can rebuild a fresh executor next time instead of
            // registerSchedule ever touching a rejected-submission executor (R7).
            synchronized(registrationLock) {
                scheduler?.shutdownNow()
                scheduler = null
            }

            // Cancel any in-flight command/pre-login coroutines and drop the references so a
            // straggling pre-login fails safe (getServerOrNull() == null) instead of racing a
            // stopped server.
            pluginScope?.cancel()
            pluginScope = null
            this.server = null
            serverData = null

            // Drop the per-enable listener/main references the same way, so a restarted server
            // (SERVER_STOPPING -> a fresh SERVER_STARTED without the mod reloading) rebuilds them
            // instead of registerEventListeners() reusing a stale FabricEventListener (R7) or
            // FabricPreLoginHandler dispatching against a FabricMain whose server/scope/listener
            // fields it just nulled above (it already treats a null `fabricMain` as "nothing to
            // do", same as its null `server`/`scope`/`eventListener` checks).
            synchronized(registrationLock) {
                fabricEventListener = null
            }
            FabricPreLoginHandler.fabricMain = null
        }

        // Register player join/disconnect events via Fabric API
        // Pre-login checks (ban) are handled via PlayerManagerMixin -> FabricPreLoginHandler

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            // Hop to the server thread before touching game state, matching kickPlayer()
            // (platform-modules-14) — DISCONNECT below can fire from a Netty I/O thread, and JOIN
            // is hopped too for consistency.
            val player = handler.player
            server?.execute {
                fabricEventListener?.onPlayerJoin(player)
            }
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            // fabric-api fires DISCONNECT from both the server thread (handleDisconnection) and a
            // Netty I/O thread (channelInactive) depending on how the client left; the latter must
            // not touch PlayerList/ServerPlayer state directly (platform-modules-14).
            val player = handler.player
            server?.execute {
                fabricEventListener?.onPlayerDisconnect(player)
            }
        }

        // Store dispatcher and register any commands already queued
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            synchronized(registrationLock) {
                registeredCommands.forEach { command ->
                    FabricCommand.register(dispatcher, command, this)
                }
            }
        }
    }

    override fun getDataFolder(): File {
        val dataDir = File("config/pano")
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        return dataDir
    }

    override fun getPanoLogger(): Logger = logger

    // NOT @Synchronized on `this` (and lifecycleLock is never held across the call below): Pano.init()
    // does `runBlocking { vertx.deployVerticle(pano).coAwait() }`, which blocks this thread until the
    // verticle's start() completes on the Vert.x event loop, and start() calls back into this class
    // (CommandManager.init() -> registerCommands(), EventManager.init() -> registerEventListeners()) —
    // both of which take registrationLock, never lifecycleLock, but the invariant (R2) is to hold no
    // lock at all across a blocking Pano call, so lifecycleLock is released before Pano.init() runs.
    // That also opens a real race, not just a hypothetical one: an Integration's `pano by lazy {
    // main.getPano() }` can resolve from onConnectionEstablished() on the event loop before this
    // thread returns from Pano.init() and publishes `mPano`, so getPano() can be re-entered
    // concurrently and must treat a second construction as a loser to dispose, not a bug.
    override fun getPano(): Pano {
        // Only ever constructed once `server` is set (SERVER_STARTED) — constructing it earlier
        // (e.g. from SERVER_STOPPING on a server that never finished starting) NPEs deep inside DI
        // because FabricServerData needs a non-null `server` (platform-modules-11).
        synchronized(lifecycleLock) {
            mPano?.let { return it }
            checkNotNull(server) { "Pano requested before the server finished starting" }
        }

        val created = Pano.init(this)

        val winner = synchronized(lifecycleLock) {
            mPano ?: created.also { mPano = it }
        }

        if (winner !== created) {
            // Lost the race: someone else already published a Pano while this thread was inside
            // Pano.init() above. Call dispose(), never disable(): disable() runs
            // `runBlocking { vertx.close() }` against the companion-object `vertxInstance`
            // SINGLETON that `winner` is deployed onto too, so it would tear down the winner's
            // Vert.x instance out from under it, not just this redundant instance. dispose() only
            // undeploys this verticle's own deployment and is fire-and-forget/non-blocking, which
            // also matters here because this whole method can be re-entered from a Vert.x event
            // loop (every Integration's `pano by lazy { main.getPano() }`) — the old disable() call
            // could park that very event loop inside `runBlocking { vertx.close() }`, which needs
            // event loops to finish closing. dispose() is documented not to throw at the caller.
            created.dispose()
        }

        return winner
    }

    override fun registerCommands(commands: List<Command>) {
        synchronized(registrationLock) {
            registeredCommands.addAll(commands)

            logger.info("Pano registerCommands called with ${commands.size} commands")

            // Try to register on stored dispatcher from CommandRegistrationCallback
            val dispatcher = server?.getCommands()?.getDispatcher()
            if (dispatcher != null) {
                commands.forEach { command ->
                    logger.info("Registering command '${command.name}' (as '${command.name.lowercase()}') on live dispatcher")
                    FabricCommand.register(dispatcher, command, this)
                }

                server?.getPlayerList()?.getPlayers()?.forEach { player ->
                    server?.getCommands()?.sendCommands(player)
                }
            } else {
                logger.warning("Server not available yet, commands will be registered when CommandRegistrationCallback fires")
            }
        }
    }

    override fun unregisterCommands(commands: List<Command>) {
        synchronized(registrationLock) {
            registeredCommands.removeAll(commands.toSet())
        }
    }

    override fun registerSchedule(task: () -> Unit) {
        synchronized(registrationLock) {
            val activeScheduler = scheduler
            if (activeScheduler == null) {
                // Only null before the first SERVER_STARTED or after SERVER_STOPPING has run (see
                // field comment) — a caller reaching this means the server isn't up; skip rather
                // than NPE.
                logger.warning("registerSchedule called while no server is running; ignoring")
                return
            }

            if (scheduledTasks.containsKey(task)) {
                stopSchedule(task)
            }

            scheduledTasks[task] = activeScheduler.scheduleAtFixedRate(task, 1, 1, TimeUnit.SECONDS)
        }
    }

    override fun stopSchedule(task: () -> Unit) {
        synchronized(registrationLock) {
            scheduledTasks[task]?.cancel(false)
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

    override fun getServerData(): ServerData =
        serverData ?: error("Pano requested server data before the server finished starting")

    override fun getPluginClassLoader(): java.net.URLClassLoader {
        // Fabric's KnotClassLoader is not a URLClassLoader, so wrap it.
        // We scan all MANIFEST.MF files and find the one with VERSION + BUILD_TYPE (Pano's manifest).
        val fabricClassLoader = FabricMain::class.java.classLoader

        var panoManifestUrl: java.net.URL? = null
        try {
            val manifestUrls = fabricClassLoader.getResources("META-INF/MANIFEST.MF")
            while (manifestUrls.hasMoreElements()) {
                val url = manifestUrls.nextElement()
                try {
                    val manifest = java.util.jar.Manifest(url.openStream())
                    if (manifest.mainAttributes.getValue("VERSION") != null &&
                        manifest.mainAttributes.getValue("BUILD_TYPE") != null
                    ) {
                        panoManifestUrl = url
                        break
                    }
                } catch (_: Exception) { /* skip unreadable manifests */
                }
            }
        } catch (_: Exception) { /* ignore */
        }

        return object : java.net.URLClassLoader(emptyArray(), fabricClassLoader) {
            override fun findResource(name: String?): java.net.URL? {
                if (name == "META-INF/MANIFEST.MF" && panoManifestUrl != null) {
                    return panoManifestUrl
                }
                return fabricClassLoader.getResource(name)
            }

            override fun findResources(name: String?) = fabricClassLoader.getResources(name)
        }
    }

    override fun translateColor(text: String): String = text.replace('&', '§')

    override fun registerEventListeners(listeners: Set<Listener>) {
        synchronized(registrationLock) {
            val current = fabricEventListener
            if (current == null) {
                // CopyOnWriteArraySet: this set is read on the server thread and Netty I/O threads far
                // more often than it is mutated, and mutation only happens from the Vert.x event loop
                // (platform-modules-9) — a plain LinkedHashSet here is not thread-safe.
                fabricEventListener = FabricEventListener(this, java.util.concurrent.CopyOnWriteArraySet(listeners))
                return
            }

            current.listeners.addAll(listeners)
        }
    }

    override fun unregisterEventListeners(listeners: Set<Listener>) {
        synchronized(registrationLock) {
            fabricEventListener?.listeners?.removeAll(listeners)
        }
    }

    // Not synchronized (R4): runs on the Vert.x event loop and only reads the @Volatile
    // `integrations` field, taking no lock at all.
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
        server?.execute {
            try {
                server?.getPlayerList()?.getPlayerByName(player)?.connection?.disconnect(
                    FabricTextHelper.parseColoredText(message)
                )
            } catch (_: Exception) {
                // Silently handle if method signatures changed between MC versions
            }
        }
    }
}
