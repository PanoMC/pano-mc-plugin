package com.panomc.plugins.pano.nukkit

import cn.nukkit.command.CommandSender
import cn.nukkit.event.EventHandler
import cn.nukkit.event.Listener
import cn.nukkit.event.player.PlayerJoinEvent
import cn.nukkit.event.player.PlayerQuitEvent
import cn.nukkit.Player
import com.panomc.plugins.pano.core.event.EventType
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain

class NukkitEventListener(
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
        listeners
            .filter { it.eventType == EventType.ON_PLAYER_JOIN }
            .forEach { listener ->
                listener.handle(this, event.player)
            }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        listeners
            .filter { it.eventType == EventType.ON_PLAYER_DISCONNECT }
            .forEach { listener ->
                listener.handle(this, event.player)
            }
    }
}
