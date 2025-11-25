package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class SpigotEventHelper(
    private val pluginMain: PanoPluginMain
) : EventHelper {

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSender).sendMessage(pluginMain.translateColor(message))
    }

    override fun kick(commandSender: Any, message: String) {
        (commandSender as Player).kickPlayer(pluginMain.translateColor(message))
    }

    override fun convertToPlayerData(player: Any): EventHelper.Companion.PlayerData {
        val playerInstance = player as Player

        return EventHelper.Companion.PlayerData(
            uuid = playerInstance.uniqueId,
            username = playerInstance.name,
            ping = playerInstance.ping.toLong()
        )
    }
}