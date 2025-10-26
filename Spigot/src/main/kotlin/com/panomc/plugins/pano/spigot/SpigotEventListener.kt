package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.event.listeners.OnPlayerDisconnect
import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class SpigotEventListener(
    private val pluginMain: PanoPluginMain,
    private val listeners: List<com.panomc.plugins.pano.core.event.Listener>
) : Listener, EventHelper {

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSender).sendMessage(pluginMain.translateColor(message))
    }

    override fun convertToPlayerData(player: Any): EventHelper.Companion.PlayerData {
        val playerInstance = player as Player

        return EventHelper.Companion.PlayerData(
            uuid = playerInstance.uniqueId,
            username = playerInstance.name,
            ping = playerInstance.ping.toLong()
        )
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        listeners.find { it is OnPlayerJoin }?.handle(this, event.player)
    }

    @EventHandler
    fun onPlayerDisconnect(event: PlayerQuitEvent) {
        listeners.find { it is OnPlayerDisconnect }?.handle(this, event.player)
    }
}