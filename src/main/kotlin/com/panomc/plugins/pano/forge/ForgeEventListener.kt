package com.panomc.plugins.pano.forge

import com.panomc.plugins.pano.core.event.EventType
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import java.util.UUID

/**
 * Placeholder event listener for Forge. Real implementations should
 * hook into Forge's event bus.
 */
class ForgeEventListener(
    private val pluginMain: PanoPluginMain,
    private val listeners: List<com.panomc.plugins.pano.core.event.Listener>
) : EventHelper {
    override fun sendMessage(commandSender: Any, message: String) {
        // Implement message sending using Forge APIs.
    }

    override fun convertToPlayerData(player: Any): EventHelper.Companion.PlayerData =
        EventHelper.Companion.PlayerData(
            uuid = UUID.randomUUID(),
            username = "unknown",
            ping = 0
        )

    fun onPlayerJoin(player: Any) {
        listeners.filter { it.eventType == EventType.ON_PLAYER_JOIN }.forEach { it.handle(this, player) }
    }

    fun onPlayerDisconnect(player: Any) {
        listeners.filter { it.eventType == EventType.ON_PLAYER_DISCONNECT }.forEach { it.handle(this, player) }
    }
}
