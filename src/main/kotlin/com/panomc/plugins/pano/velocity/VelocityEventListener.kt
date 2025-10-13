package com.panomc.plugins.pano.velocity

import com.panomc.plugins.pano.core.event.EventType
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component

class VelocityEventListener(
    private val pluginMain: PanoPluginMain,
    private val listeners: List<Listener>
) : EventHelper {

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSource).sendMessage(Component.text(pluginMain.translateColor(message)))
    }

    override fun convertToPlayerData(player: Any): EventHelper.Companion.PlayerData {
        val playerInstance = player as Player

        return EventHelper.Companion.PlayerData(
            uuid = playerInstance.uniqueId,
            username = playerInstance.username,
            ping = playerInstance.ping
        )
    }

    @Subscribe
    fun onPlayerJoin(event: PostLoginEvent) {
        listeners
            .filter { it.eventType == EventType.ON_PLAYER_JOIN }
            .forEach { listener ->
                listener.handle(this, event.player)
            }
    }

    @Subscribe
    fun onPlayerDisconnect(event: DisconnectEvent) {
        listeners
            .filter { it.eventType == EventType.ON_PLAYER_DISCONNECT }
            .forEach { listener ->
                listener.handle(this, event.player)
            }
    }
}