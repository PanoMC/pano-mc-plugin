package com.panomc.plugins.pano.bungee

import com.panomc.plugins.pano.core.event.EventType
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.event.EventHandler


class BungeeEventListener(
    private val pluginMain: PanoPluginMain,
    private val listeners: List<com.panomc.plugins.pano.core.event.Listener>
) : Listener, EventHelper {

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSender).sendMessage(TextComponent(pluginMain.translateColor(message)))
    }

    override fun convertToPlayerData(player: Any): EventHelper.Companion.PlayerData {
        val playerInstance = player as ProxiedPlayer

        return EventHelper.Companion.PlayerData(
            uuid = playerInstance.uniqueId,
            username = playerInstance.name,
            ping = playerInstance.ping.toLong()
        )
    }

    @EventHandler
    fun onPostLogin(event: PostLoginEvent) {
        listeners
            .filter { it.eventType == EventType.ON_PLAYER_JOIN }
            .forEach { listener ->
                listener.handle(this, event.player)
            }
    }

    @EventHandler
    fun onPlayerDisconnect(event: PlayerDisconnectEvent) {
        listeners
            .filter { it.eventType == EventType.ON_PLAYER_DISCONNECT }
            .forEach { listener ->
                listener.handle(this, event.player)
            }
    }
}