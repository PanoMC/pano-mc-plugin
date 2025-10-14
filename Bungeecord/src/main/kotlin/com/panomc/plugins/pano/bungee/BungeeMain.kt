package com.panomc.plugins.pano.bungee

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.scheduler.ScheduledTask
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class BungeeMain : Plugin(), PanoPluginMain {
    private lateinit var pano: Pano
    private val scheduledTasks = mutableMapOf<() -> Unit, ScheduledTask>()
    private val serverData by lazy { BungeeServerData(this) }

    override fun onEnable() {
        pano = Pano.init(this)

        pano.onServerStart()
    }

    override fun onDisable() {
        if (::pano.isInitialized) {
            pano.disable()
        }
    }

    override fun registerCommands(commands: List<Command>) {
        commands
            .map { BungeeCommand(it, this) }
            .forEach { command ->
                proxy.pluginManager.registerCommand(this, command)
            }
    }

    override fun unregisterCommands(commands: List<Command>) {
        proxy.pluginManager.unregisterCommands(this)
    }

    override fun registerSchedule(task: () -> Unit) {
        if (scheduledTasks.containsKey(task)) {
            stopSchedule(task)
        }

        scheduledTasks[task] = proxy.scheduler.schedule(this, {
            task.invoke()

            registerSchedule(task)
        }, 1, TimeUnit.SECONDS)
    }

    override fun stopSchedule(task: () -> Unit) {
        scheduledTasks[task]?.cancel()
        scheduledTasks.remove(task)
    }

    override fun unregisterSchedules(tasks: List<() -> Unit>) {
        tasks.forEach { task ->
            stopSchedule(task)
        }
    }

    override fun getServerData(): ServerData = serverData

    override fun getPluginClassLoader(): URLClassLoader = javaClass.classLoader as URLClassLoader

    override fun translateColor(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

    override fun registerEventListeners(listeners: List<Listener>) {
        proxy.pluginManager.registerListener(this, BungeeEventListener(this, listeners))
    }

    override fun unregisterEventListeners(listeners: List<Listener>) {
        proxy.pluginManager.unregisterListeners(this)
    }

    override fun getPanoLogger(): Logger = logger
}