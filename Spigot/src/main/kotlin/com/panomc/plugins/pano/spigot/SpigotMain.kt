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
import java.net.URLClassLoader
import java.util.*
import java.util.function.Consumer
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

                commandMap.register(name, command)
            }
    }

    override fun unregisterCommands(commands: List<Command>) {
        val commandMap = getCommandMap()

        this.commands
            .forEach { command ->
                command.unregister(commandMap)
            }

        this.commands.clear()
    }

    private fun getCommandMap(): CommandMap {
        val commandMapField = Bukkit.getServer().javaClass.getDeclaredField("commandMap")

        commandMapField.isAccessible = true

        return commandMapField.get(Bukkit.getServer()) as CommandMap
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

    override fun getPluginClassLoader(): URLClassLoader = classLoader as URLClassLoader

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
        server.scheduler.runTask(this, Runnable {
            server.getPlayer(player)?.kickPlayer(message)
        })
    }

    /**
     * Matches the server’s own never-joined / offline name→UUID mapping (version-accurate).
     */
    override fun getNeverJoinedPlayerUniqueId(minecraftName: String): UUID {
        return Bukkit.getOfflinePlayer(minecraftName).uniqueId
    }
}