package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.integration.BanIntegration
import com.panomc.plugins.pano.core.integration.PermissionIntegration
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import com.panomc.plugins.pano.spigot.integration.AuthMeIntegration
import io.vertx.core.http.WebSocket
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandMap
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.net.URL
import java.net.URLClassLoader
import java.util.*
import java.util.function.Consumer
import java.util.jar.Manifest
import java.util.logging.Logger

class SpigotMain : JavaPlugin(), PanoPluginMain {
    private val mPano by lazy {
        Pano.init(this)
    }
    private val commands = mutableListOf<SpigotCommand>()
    private val scheduledTasks = mutableMapOf<() -> Unit, Any>()
    private val serverData by lazy { SpigotServerData(this) }
    private val mPanoLogger by lazy { getPanoLogger() }
    internal val eventHelper by lazy { SpigotEventHelper(this) }
    internal lateinit var spigotEventListener: SpigotEventListener

    private val integrations by lazy {
        listOf(
            PermissionIntegration(this),
            BanIntegration(this),
            AuthMeIntegration(this),
        )
    }

    override fun onEnable() {
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
        integrations.forEach { it.onEnable() }

        mPano.onServerStart()
    }

    override fun onDisable() {
        // Integrations first: they may need to schedule Bukkit tasks while the plugin is still
        // enabled. Calling mPano.disable() first closes Vert.x asynchronously; the WebSocket
        // onDisconnect can fire after the plugin is already disabled, which would break
        // AuthMeIntegration's reload (runTask on a disabled plugin).
        integrations.forEach { it.onDisable() }

        mPano.disable()
    }

    override fun registerCommands(commands: List<Command>) {
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

        syncRegisteredCommands()
    }

    override fun unregisterCommands(commands: List<Command>) {
        val commandMap = getCommandMap()

        this.commands
            .forEach { command ->
                command.unregister(commandMap)
            }

        this.commands.clear()

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

    override fun stopSchedule(task: () -> Unit) {
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

    override fun unregisterSchedules(tasks: List<() -> Unit>) {
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
        if (::spigotEventListener.isInitialized) {
            spigotEventListener.listeners.addAll(listeners)
            return
        }

        spigotEventListener = SpigotEventListener(eventHelper, listeners.toMutableSet())

        server.pluginManager.registerEvents(spigotEventListener, this)
    }

    override fun unregisterEventListeners(listeners: Set<Listener>) {
        spigotEventListener.listeners.removeAll(listeners)
    }

    override fun getPanoLogger(): Logger = ColoredLogger("[Pano] ")

    override fun getPano(): Pano = mPano

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
        SpigotServerUtil.kickPlayer(this, server.getPlayer(player) ?: return, message)
    }

    /**
     * Matches the server’s own never-joined / offline name→UUID mapping (version-accurate).
     */
    override fun getNeverJoinedPlayerUniqueId(minecraftName: String): UUID {
        return Bukkit.getOfflinePlayer(minecraftName).uniqueId
    }
}