package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import io.vertx.core.http.WebSocket
import org.bukkit.event.Listener

interface Integration : Listener {
    fun onEnable() {}

    fun onDisable() {}

    fun onConnectionEstablished(webSocket: WebSocket?) {}

    fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {}

    fun isInitialized(): Boolean
}