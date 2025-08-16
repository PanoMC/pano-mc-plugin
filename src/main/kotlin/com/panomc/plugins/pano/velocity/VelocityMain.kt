package com.panomc.plugins.pano.velocity

import com.google.inject.Inject
import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyReloadEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

@Plugin(
    id = "pano",
    name = "Pano",
    version = BuildConfig.VERSION, // <- compile-time const
    url = "https://panomc.com",
    description = "An advanced web platform for Minecraft servers.",
    authors = ["Pano MC"]
)
class VelocityMain : PanoPluginMain {
    private lateinit var pano: Pano
    private val commands = mutableMapOf<VelocityCommand, CommandMeta>()
    private val scheduledTasks = mutableMapOf<() -> Unit, ScheduledTask>()

    @Inject
    private lateinit var server: ProxyServer

    @Inject
    private lateinit var logger: Logger

    @Inject
    @DataDirectory
    private lateinit var dataFolder: Path

    private val serverData by lazy { VelocityServerData(server) }

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        onEnable()
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        onDisable()
    }

    @Subscribe
    fun onProxyReload(event: ProxyReloadEvent) {
        onDisable()
        onEnable()
    }

    override fun getDataFolder(): File = dataFolder.toFile()

    override fun getLogger(): Logger = logger

    private fun onEnable() {
        pano = Pano.init(this)

        val task: () -> Unit = {
            if (::pano.isInitialized) {
                pano.onServerStart()
            }
        }

        server.scheduler
            .buildTask(this, task)
            .schedule()
    }

    private fun onDisable() {
        if (::pano.isInitialized) {
            pano.disable()
        }
    }

    override fun registerCommands(commands: List<Command>) {
        commands
            .forEach { command ->
                val commandManager = server.commandManager
                val velocityCommand = VelocityCommand(command, this)

                val commandMeta = commandManager.metaBuilder(command.name)
                    .plugin(this)
                    .build()

                commandManager.register(commandMeta, velocityCommand)

                this.commands[velocityCommand] = commandMeta
            }
    }

    override fun unregisterCommands(commands: List<Command>) {
        this.commands
            .forEach { command ->
                val commandManager = server.commandManager

                commandManager.unregister(command.value)
            }

        this.commands.clear()
    }

    override fun registerSchedule(task: () -> Unit) {
        if (scheduledTasks.containsKey(task)) {
            stopSchedule(task)
        }

        scheduledTasks[task] = server.scheduler
            .buildTask(this, task)
            .repeat(1L, TimeUnit.SECONDS)
            .schedule()
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

    override fun getPluginClassLoader(): URLClassLoader = VelocityMain::class.java.classLoader as URLClassLoader

    override fun translateColor(text: String): String = text.replace("&", "§")

    override fun registerEventListeners(listeners: List<Listener>) {
        server.eventManager.register(this, VelocityEventListener(this, listeners))
    }

    override fun unregisterEventListeners(listeners: List<Listener>) {
        server.eventManager.unregisterListeners(this)
    }
}