package com.panomc.plugins.pano.core.helper

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import io.vertx.core.http.WebSocket
import java.io.File
import java.net.URLClassLoader
import java.util.logging.Logger

interface PanoPluginMain {
    fun getDataFolder(): File

    fun getPanoLogger(): Logger

    fun getPano(): Pano

    fun registerCommands(commands: List<Command>)

    fun unregisterCommands(commands: List<Command>)

    fun registerSchedule(task: () -> Unit)

    fun stopSchedule(task: () -> Unit)

    fun unregisterSchedules(tasks: List<() -> Unit>)

    fun getServerData(): ServerData

    fun getPluginClassLoader(): URLClassLoader

    fun translateColor(text: String): String

    fun registerEventListeners(listeners: Set<Listener>)

    fun unregisterEventListeners(listeners: Set<Listener>)

    fun onConnectionEstablished(webSocket: WebSocket?) {}

    fun onDisconnect() {}

    fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {}

    fun onPermissionsSnapshotUpdated(message: com.panomc.plugins.pano.core.platform.message.response.PermissionsSnapshotUpdatedMessage) {}

    fun kickPlayer(player: String, message: String)
}