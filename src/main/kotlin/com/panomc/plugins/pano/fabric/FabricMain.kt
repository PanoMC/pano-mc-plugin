package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import net.fabricmc.api.DedicatedServerModInitializer
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class FabricMain : DedicatedServerModInitializer, PanoPluginMain {
    private lateinit var pano: Pano
    private val logger: Logger = Logger.getLogger("Pano")
    private val serverData = FabricServerData()
    private val scheduler = Executors.newScheduledThreadPool(1)
    private val scheduledTasks = ConcurrentHashMap<() -> Unit, ScheduledFuture<*>>()

    override fun onInitializeServer() {
        pano = Pano.init(this)
        pano.onServerStart()
    }

    override fun getDataFolder(): File = File("config/pano")

    override fun getLogger(): Logger = logger

    override fun registerCommands(commands: List<Command>) {
        // Fabric command registration requires Minecraft classes; not implemented
    }

    override fun unregisterCommands(commands: List<Command>) {
        // No-op for now
    }

    override fun registerSchedule(task: () -> Unit) {
        stopSchedule(task)
        val future = scheduler.scheduleAtFixedRate(task, 1, 1, TimeUnit.SECONDS)
        scheduledTasks[task] = future
    }

    override fun stopSchedule(task: () -> Unit) {
        scheduledTasks.remove(task)?.cancel(false)
    }

    override fun unregisterSchedules(tasks: List<() -> Unit>) {
        tasks.forEach { stopSchedule(it) }
    }

    override fun getServerData(): ServerData = serverData

    override fun getPluginClassLoader(): URLClassLoader = FabricMain::class.java.classLoader as URLClassLoader

    override fun translateColor(text: String): String = text.replace("&", "§")

    override fun registerEventListeners(listeners: List<Listener>) {
        // Fabric events require Minecraft classes; not implemented
    }

    override fun unregisterEventListeners(listeners: List<Listener>) {
        // No-op for now
    }
}
