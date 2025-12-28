package com.panomc.plugins.pano.bungee

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.integration.BanIntegration
import com.panomc.plugins.pano.core.integration.PermissionIntegration
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import com.panomc.plugins.pano.core.platform.message.response.PermissionsSnapshotUpdatedMessage
import io.vertx.core.http.WebSocket
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.scheduler.ScheduledTask
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class BungeeMain : Plugin(), PanoPluginMain {
    private val mPano: Pano by lazy {
        Pano.init(this)
    }
    private val scheduledTasks = mutableMapOf<() -> Unit, ScheduledTask>()
    private val serverData by lazy { BungeeServerData(this) }
    internal lateinit var bungeeEventListener: BungeeEventListener

    private val integrations by lazy {
        listOf(
            PermissionIntegration(this),
            BanIntegration(this),
        )
    }

    override fun onEnable() {
        integrations.forEach { it.onEnable() }

        mPano.onServerStart()
    }

    override fun onDisable() {
        mPano.disable()

        integrations.forEach { it.onDisable() }
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

    override fun registerEventListeners(listeners: Set<Listener>) {
        if (!::bungeeEventListener.isInitialized) {
            bungeeEventListener = BungeeEventListener(this, listeners.toMutableSet())
        }

        bungeeEventListener.listeners.addAll(listeners)

        proxy.pluginManager.registerListener(this, BungeeEventListener(this, listeners.toMutableSet()))
    }

    override fun unregisterEventListeners(listeners: Set<Listener>) {
        bungeeEventListener.listeners.removeAll(listeners)
    }

    override fun getPanoLogger(): Logger = logger

    override fun getPano(): Pano = mPano

    override fun onConnectionEstablished(webSocket: WebSocket?) {
        integrations.forEach { it.onConnectionEstablished(webSocket) }
    }

    override fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {
        integrations.forEach { it.onServerSettingsChanged(serverSettings) }
    }

    override fun onPermissionsSnapshotUpdated(message: PermissionsSnapshotUpdatedMessage) {
        integrations.forEach { it.onPermissionsSnapshotUpdated(message) }
    }

    override fun kickPlayer(player: String, message: String) {
        proxy.getPlayer(player)?.disconnect(message)
    }
}