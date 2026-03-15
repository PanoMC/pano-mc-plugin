package com.panomc.plugins.pano.core.helper

import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import io.vertx.core.http.WebSocket

interface Integration {
    fun onEnable() {}

    fun onDisable() {}

    fun onConnectionEstablished(webSocket: WebSocket?) {}

    fun onDisconnect() {}

    fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {}

    fun onPermissionsSnapshotUpdated(message: com.panomc.plugins.pano.core.platform.message.response.PermissionsSnapshotUpdatedMessage) {}

    fun isInitialized(): Boolean

    val panoPluginMain: PanoPluginMain

    fun String.colorize() = panoPluginMain.translateColor(this)
}