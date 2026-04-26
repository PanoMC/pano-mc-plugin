package com.panomc.plugins.pano.core.event.listeners

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.request.OnPlayerJoinRequest

open class OnPlayerJoin(private val platformManager: PlatformManager, private val pluginMain: PanoPluginMain) : Listener {
    override suspend fun handle(eventHelper: EventHelper, vararg args: Any) {
        if (platformManager.getWebSocket() == null) {
            return
        }

        val player = args[0]
        val playerData = eventHelper.convertToPlayerData(player)

        var playerCount = pluginMain.getServerData().playerCount()

        // On these platforms, the JOIN event fires before the player is fully added to the list
        if (pluginMain.getServerData().serverType() in listOf(
                ServerType.FABRIC,
            )
        ) {
            playerCount += 1
        }

        val eventRequest = OnPlayerJoinRequest(playerData, playerCount)

        platformManager.sendMessage(eventRequest)
    }
}