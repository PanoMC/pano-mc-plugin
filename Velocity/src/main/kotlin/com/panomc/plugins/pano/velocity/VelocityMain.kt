package com.panomc.plugins.pano.velocity

import com.google.inject.Inject
import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.integration.BanIntegration
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import com.panomc.plugins.pano.core.util.LegacyColorConverter
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyReloadEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import io.vertx.core.http.WebSocket
import net.kyori.adventure.text.Component
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class VelocityMain : PanoPluginMain {
    private val mPano: Pano by lazy {
        Pano.init(this)
    }

    private val commands = mutableMapOf<VelocityCommand, CommandMeta>()
    private val scheduledTasks = mutableMapOf<() -> Unit, ScheduledTask>()
    internal lateinit var velocityEventListener: VelocityEventListener

    private val integrations by lazy {
        listOf(
            BanIntegration(this),
        )
    }

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

    override fun getPanoLogger(): Logger = logger

    override fun getPano(): Pano = mPano

    private fun onEnable() {
        integrations.forEach { it.onEnable() }

        val task: () -> Unit = {
            mPano.onServerStart()
        }

        server.scheduler
            .buildTask(this, task)
            .schedule()
    }

    private fun onDisable() {
        mPano.disable()

        integrations.forEach { it.onDisable() }
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

    override fun translateColor(text: String): String = LegacyColorConverter.translate(text)

    override fun registerEventListeners(listeners: Set<Listener>) {
        if (!::velocityEventListener.isInitialized) {
            velocityEventListener = VelocityEventListener(this, listeners.toMutableSet())
        }

        velocityEventListener.listeners.addAll(listeners)

        server.eventManager.register(this, velocityEventListener)
    }

    override fun unregisterEventListeners(listeners: Set<Listener>) {
        server.eventManager.unregisterListeners(this)
    }

    override fun onConnectionEstablished(webSocket: WebSocket?) {
        integrations.forEach { it.onConnectionEstablished(webSocket) }
    }

    override fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {
        integrations.forEach { it.onServerSettingsChanged(serverSettings) }
    }

    override fun kickPlayer(player: String, message: String) {
        val optionalPlayer = server.getPlayer(player)

        if (optionalPlayer.isPresent) {
            optionalPlayer.get().disconnect(Component.text(translateColor(message)))
        }
    }
}