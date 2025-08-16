package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class FabricMain : DedicatedServerModInitializer, PanoPluginMain {
    private lateinit var pano: Pano
    private lateinit var server: MinecraftServer
    private val logger: Logger = Logger.getLogger("Pano")
    private val scheduledTasks = mutableMapOf<() -> Unit, ScheduledFuture<*>>()
    private val scheduler = Executors.newScheduledThreadPool(1)
    private val eventListeners = mutableListOf<FabricEventListener>()
    private val serverData by lazy { FabricServerData(server) }

    override fun onInitializeServer(server: MinecraftServer) {
        this.server = server
        pano = Pano.init(this)

        ServerLifecycleEvents.SERVER_STARTED.register {
            if (::pano.isInitialized) {
                pano.onServerStart()
            }
        }
    }

    override fun getDataFolder(): File =
        FabricLoader.getInstance().configDir.resolve("pano").toFile()

    override fun getLogger(): Logger = logger

    override fun registerCommands(commands: List<Command>) {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            commands.map { FabricCommand(it, this) }.forEach { it.register(dispatcher) }
        }
    }

    override fun unregisterCommands(commands: List<Command>) {
        // Fabric commands cannot be unregistered at runtime
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
        tasks.forEach { task -> stopSchedule(task) }
    }

    override fun getServerData(): ServerData = serverData

    override fun getPluginClassLoader(): URLClassLoader = FabricMain::class.java.classLoader as URLClassLoader

    override fun translateColor(text: String): String = text.replace("&", "§")

    override fun registerEventListeners(listeners: List<Listener>) {
        val fabricListener = FabricEventListener(this, listeners)
        eventListeners.add(fabricListener)
    }

    override fun unregisterEventListeners(listeners: List<Listener>) {
        eventListeners.forEach { it.unregister() }
        eventListeners.clear()
    }

    fun sendMessage(source: ServerCommandSource, message: String) {
        source.sendFeedback({ Text.literal(translateColor(message)) }, false)
    }
}
