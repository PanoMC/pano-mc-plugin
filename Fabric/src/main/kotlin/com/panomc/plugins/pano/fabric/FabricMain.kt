package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.integration.BanIntegration
import com.panomc.plugins.pano.core.integration.PermissionIntegration
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import com.panomc.plugins.pano.core.platform.message.response.PermissionsSnapshotUpdatedMessage
import io.vertx.core.http.WebSocket
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
    private val mPano: Pano by lazy {
        Pano.init(this)
    }

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pano-scheduler").apply { isDaemon = true }
    }
    private val scheduledTasks = mutableMapOf<() -> Unit, ScheduledFuture<*>>()
    private val registeredCommands = mutableListOf<Command>()

    internal lateinit var fabricEventListener: FabricEventListener

    internal fun getFabricEventListenerOrNull(): FabricEventListener? {
        return if (::fabricEventListener.isInitialized) fabricEventListener else null
    }

    private var server: MinecraftServer? = null
    private val serverData by lazy { FabricServerData(server!!) }

    // Bridge JUL → SLF4J so logs use Fabric's native format
    private val logger: Logger = FabricLogger("Pano")

    private val integrations by lazy {
        listOf(
            PermissionIntegration(this),
            BanIntegration(this),
        )
    }

    override fun onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            this.server = server

            FabricPreLoginHandler.fabricMain = this

            integrations.forEach { it.onEnable() }

            mPano.onServerStart()
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            mPano.disable()

            integrations.forEach { it.onDisable() }

            scheduler.shutdownNow()
        }

        // Register player join/disconnect events via Fabric API
        // Pre-login checks (ban) are handled via PlayerManagerMixin -> FabricPreLoginHandler

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            if (::fabricEventListener.isInitialized) {
                fabricEventListener.onPlayerJoin(handler.player)
            }
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            if (::fabricEventListener.isInitialized) {
                fabricEventListener.onPlayerDisconnect(handler.player)
            }
        }

        // Store dispatcher and register any commands already queued
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registeredCommands.forEach { command ->
                FabricCommand.register(dispatcher, command, this)
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

    override fun getPano(): Pano = mPano

    override fun registerCommands(commands: List<Command>) {
        registeredCommands.addAll(commands)

        logger.info("Pano registerCommands called with ${commands.size} commands")

        // Try to register on stored dispatcher from CommandRegistrationCallback
        val dispatcher = server?.commandManager?.dispatcher
        if (dispatcher != null) {
            commands.forEach { command ->
                logger.info("Registering command '${command.name}' (as '${command.name.lowercase()}') on live dispatcher")
                FabricCommand.register(dispatcher, command, this)
            }

            // Send updated command tree to all online players
            server?.playerManager?.playerList?.forEach { player ->
                server?.commandManager?.sendCommandTree(player)
            }
        } else {
            logger.warning("Server not available yet, commands will be registered when CommandRegistrationCallback fires")
        }
    }

    override fun unregisterCommands(commands: List<Command>) {
        registeredCommands.removeAll(commands.toSet())
    }

    override fun registerSchedule(task: () -> Unit) {
        if (scheduledTasks.containsKey(task)) {
            stopSchedule(task)
        }

        scheduledTasks[task] = scheduler.scheduleAtFixedRate(task, 1, 1, TimeUnit.SECONDS)
    }

    override fun stopSchedule(task: () -> Unit) {
        scheduledTasks[task]?.cancel(false)
        scheduledTasks.remove(task)
    }

    override fun unregisterSchedules(tasks: List<() -> Unit>) {
        tasks.forEach { task ->
            stopSchedule(task)
        }
    }

    override fun getServerData(): ServerData = serverData

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
        if (!::fabricEventListener.isInitialized) {
            fabricEventListener = FabricEventListener(this, listeners.toMutableSet())
        }

        fabricEventListener.listeners.addAll(listeners)
    }

    override fun unregisterEventListeners(listeners: Set<Listener>) {
        if (::fabricEventListener.isInitialized) {
            fabricEventListener.listeners.removeAll(listeners)
        }
    }

    override fun onConnectionEstablished(webSocket: WebSocket?) {
        integrations.forEach { it.onConnectionEstablished(webSocket) }
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
                server?.playerManager?.getPlayer(player)?.networkHandler?.disconnect(
                    FabricTextHelper.parseColoredText(message)
                )
            } catch (_: Exception) {
                // Silently handle if method signatures changed between MC versions
            }
        }
    }
}
