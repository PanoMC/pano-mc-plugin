package com.panomc.plugins.pano.core.event.listeners

import com.panomc.plugins.pano.core.PlatformManager
import com.panomc.plugins.pano.core.ServerEvent
import com.panomc.plugins.pano.core.event.EventType
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain

class OnPlayerJoin(private val platformManager: PlatformManager, private val pluginMain: PanoPluginMain) : Listener {
    override val eventType: EventType = EventType.ON_PLAYER_JOIN

    override fun handle(eventHelper: EventHelper, vararg args: Any) {
        val player = args[0]
        val playerData = eventHelper.convertToPlayerData(player)

        val eventRequest = platformManager.createEventRequest(ServerEvent.ON_PLAYER_JOIN)

        eventRequest.put("player", playerData)
        eventRequest.put("playerCount", pluginMain.getServerData().playerCount())

        platformManager.getWebSocket()?.writeTextMessage(eventRequest.encode())
    }
}