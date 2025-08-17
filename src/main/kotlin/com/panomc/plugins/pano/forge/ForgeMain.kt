package com.panomc.plugins.pano.forge

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Basic skeleton implementation for Forge servers.
 * Actual Forge event and command registration should be
 * implemented using Forge APIs at runtime.
 */
class ForgeMain : PanoPluginMain {
    private val logger: Logger = Logger.getLogger("Pano")
    private lateinit var pano: Pano
    private val commands = mutableListOf<Command>()
    private val scheduledTasks = ConcurrentHashMap<() -> Unit, ScheduledFuture<*>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val serverData by lazy { ForgeServerData() }

    fun onServerStarting() {
        pano = Pano.init(this)
        pano.onServerStart()
    }

    fun onServerStopping() {
        if (::pano.isInitialized) {
            pano.disable()
        }
    }

    override fun getDataFolder(): File = File("plugins/pano")

    override fun getLogger(): Logger = logger

    override fun registerCommands(commands: List<Command>) {
        // Command registration should be handled with Forge APIs.
        this.commands.addAll(commands)
    }

    override fun unregisterCommands(commands: List<Command>) {
        // Forge does not currently support unregistering commands at runtime.
        this.commands.removeAll(commands)
    }

    override fun registerSchedule(task: () -> Unit) {
        scheduledTasks[task]?.cancel(false)
        scheduledTasks[task] = scheduler.scheduleAtFixedRate(task, 1, 1, TimeUnit.SECONDS)
    }

    override fun stopSchedule(task: () -> Unit) {
        scheduledTasks.remove(task)?.cancel(false)
    }

    override fun unregisterSchedules(tasks: List<() -> Unit>) {
        tasks.forEach { stopSchedule(it) }
    }

    override fun getServerData(): ServerData = serverData

    override fun getPluginClassLoader(): URLClassLoader = this::class.java.classLoader as URLClassLoader

    override fun translateColor(text: String): String = text

    override fun registerEventListeners(listeners: List<Listener>) {
        // Forge event registration should be implemented via MinecraftForge.EVENT_BUS at runtime.
    }

    override fun unregisterEventListeners(listeners: List<Listener>) {
        // No-op for now.
    }
}
