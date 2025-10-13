package com.panomc.plugins.pano.core.event.listeners

import com.panomc.plugins.pano.core.PlatformManager
import com.panomc.plugins.pano.core.ServerEvent
import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.event.EventType
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain

class OnPlayerDisconnect(private val platformManager: PlatformManager, private val pluginMain: PanoPluginMain) :
    Listener {
    override val eventType: EventType = EventType.ON_PLAYER_DISCONNECT

    override fun handle(eventHelper: EventHelper, vararg args: Any) {
        val player = args[0]
        val playerData = eventHelper.convertToPlayerData(player)

        val eventRequest = platformManager.createEventRequest(ServerEvent.ON_PLAYER_DISCONNECT)

        var playerCount = pluginMain.getServerData().playerCount()

        if (pluginMain.getServerData().serverType() in listOf(
                ServerType.FOLIA,
                ServerType.PAPER,
                ServerType.SPIGOT,
                ServerType.BUKKIT,
                ServerType.BUNGEECORD,
            )
        ) {
            playerCount -= 1
        }

        eventRequest.put("player", playerData)
        eventRequest.put("playerCount", playerCount)

        platformManager.getWebSocket()?.writeTextMessage(eventRequest.encode())
    }
}