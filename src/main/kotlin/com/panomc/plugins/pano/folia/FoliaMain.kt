package com.panomc.plugins.pano.folia

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.spigot.ColoredLogger
import org.bukkit.ChatColor
import org.bukkit.command.CommandMap
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import java.net.URLClassLoader
import java.util.logging.Logger

/**
 * Folia implementation of [PanoPluginMain]. Most functionality mirrors the
 * Spigot implementation but utilises Folia's scheduler.
 */
class FoliaMain : JavaPlugin(), PanoPluginMain {
    private lateinit var pano: Pano
    private val commands = mutableListOf<FoliaCommand>()
    private val scheduledTasks = mutableMapOf<() -> Unit, Any>()
    private val serverData by lazy { FoliaServerData(this) }

    override fun onEnable() {
        pano = Pano.init(this)

        try {
            val scheduler = server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server)
            val runDelayed = scheduler.javaClass.getMethod(
                "runDelayed",
                org.bukkit.plugin.Plugin::class.java,
                Runnable::class.java,
                Long::class.javaPrimitiveType
            )
            runDelayed.invoke(scheduler, this, Runnable {
                if (::pano.isInitialized) {
                    pano.onServerStart()
                }
            }, 1L)
        } catch (exception: Exception) {
            logger.severe("Failed to schedule start task: ${exception.message}")
        }
    }

    override fun onDisable() {
        if (::pano.isInitialized) {
            pano.disable()
        }
    }

    override fun registerCommands(commands: List<Command>) {
        val commandMap = getCommandMap()

        commands
            .map { FoliaCommand(it, this) }
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
        val commandMapField = server.javaClass.getDeclaredField("commandMap")

        commandMapField.isAccessible = true

        return commandMapField.get(server) as CommandMap
    }

    override fun registerSchedule(task: () -> Unit) {
        if (scheduledTasks.containsKey(task)) {
            stopSchedule(task)
        }

        try {
            val scheduler = server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server)
            val runAtFixedRate = scheduler.javaClass.getMethod(
                "runAtFixedRate",
                org.bukkit.plugin.Plugin::class.java,
                Runnable::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            )
            val scheduled = runAtFixedRate.invoke(scheduler, this, Runnable { task() }, 1L, 20L)
            scheduledTasks[task] = scheduled
        } catch (exception: Exception) {
            logger.severe("Failed to schedule task: ${exception.message}")
        }
    }

    override fun stopSchedule(task: () -> Unit) {
        scheduledTasks[task]?.let { scheduled ->
            try {
                scheduled.javaClass.getMethod("cancel").invoke(scheduled)
            } catch (exception: Exception) {
                logger.severe("Failed to cancel task: ${exception.message}")
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

    override fun registerEventListeners(listeners: List<Listener>) {
        server.pluginManager.registerEvents(FoliaEventListener(this, listeners), this)
    }

    override fun unregisterEventListeners(listeners: List<Listener>) {
        HandlerList.unregisterAll(this)
    }

    override fun getLogger(): Logger = ColoredLogger("[Pano] ")
}

