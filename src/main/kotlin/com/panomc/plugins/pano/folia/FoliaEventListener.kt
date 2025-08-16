package com.panomc.plugins.pano.folia

import com.panomc.plugins.pano.core.event.EventType
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Bridges core event listeners to Folia's event system.
 */
class FoliaEventListener(
    private val pluginMain: PanoPluginMain,
    private val listeners: List<com.panomc.plugins.pano.core.event.Listener>,
) : Listener, EventHelper {

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSender).sendMessage(pluginMain.translateColor(message))
    }

    override fun convertToPlayerData(player: Any): EventHelper.Companion.PlayerData {
        val playerInstance = player as Player

        return EventHelper.Companion.PlayerData(
            uuid = playerInstance.uniqueId,
            username = playerInstance.name,
            ping = playerInstance.ping.toLong(),
        )
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        listeners
            .filter { it.eventType == EventType.ON_PLAYER_JOIN }
            .forEach { listener ->
                listener.handle(this, event.player)
                event.player.uniqueId
            }
    }

    @EventHandler
    fun onPlayerDisconnect(event: PlayerQuitEvent) {
        listeners
            .filter { it.eventType == EventType.ON_PLAYER_DISCONNECT }
            .forEach { listener ->
                listener.handle(this, event.player)
            }
    }
}

