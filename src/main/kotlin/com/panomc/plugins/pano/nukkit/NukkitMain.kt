package com.panomc.plugins.pano.nukkit

import cn.nukkit.plugin.PluginBase
import cn.nukkit.utils.TextFormat
import cn.nukkit.event.HandlerList
import cn.nukkit.scheduler.TaskHandler
import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import java.net.URLClassLoader

class NukkitMain : PluginBase(), PanoPluginMain {
    private lateinit var pano: Pano
    private val commands = mutableListOf<NukkitCommand>()
    private val scheduledTasks = mutableMapOf<() -> Unit, TaskHandler>()
    private val serverData by lazy { NukkitServerData(this) }

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
        commands.map { NukkitCommand(it, this) }.forEach { cmd ->
            this.commands.add(cmd)
            server.commandMap.register(name, cmd)
        }
    }

    override fun unregisterCommands(commands: List<Command>) {
        this.commands.forEach { cmd ->
            cmd.unregister(server.commandMap)
        }
        this.commands.clear()
    }

    override fun registerSchedule(task: () -> Unit) {
        if (scheduledTasks.containsKey(task)) {
            stopSchedule(task)
        }
        val handler = server.scheduler.scheduleRepeatingTask(this, Runnable { task.invoke() }, 20)
        scheduledTasks[task] = handler
    }

    override fun stopSchedule(task: () -> Unit) {
        scheduledTasks[task]?.cancel()
        scheduledTasks.remove(task)
    }

    override fun unregisterSchedules(tasks: List<() -> Unit>) {
        tasks.forEach { stopSchedule(it) }
    }

    override fun getServerData(): ServerData = serverData

    override fun getPluginClassLoader(): URLClassLoader = javaClass.classLoader as URLClassLoader

    override fun translateColor(text: String): String = TextFormat.colorize('&', text)

    override fun registerEventListeners(listeners: List<Listener>) {
        server.pluginManager.registerEvents(NukkitEventListener(this, listeners), this)
    }

    override fun unregisterEventListeners(listeners: List<Listener>) {
        HandlerList.unregisterAll(this)
    }
}
