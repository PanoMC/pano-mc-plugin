package com.panomc.plugins.pano.core.event.listeners

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.request.OnPlayerDisconnectRequest

class OnPlayerDisconnect(private val platformManager: PlatformManager, private val pluginMain: PanoPluginMain) :
    Listener {
    override suspend fun handle(eventHelper: EventHelper, vararg args: Any) {
        val player = args[0]
        val playerData = eventHelper.convertToPlayerData(player)

        var playerCount = pluginMain.getServerData().playerCount()

        if (pluginMain.getServerData().serverType() in listOf(
                ServerType.FABRIC,
                ServerType.FOLIA,
                ServerType.PAPER,
                ServerType.SPIGOT,
                ServerType.BUKKIT,
                ServerType.BUNGEECORD,
            )
        ) {
            playerCount -= 1
        }

        val eventRequest = OnPlayerDisconnectRequest(playerData, playerCount)

        platformManager.sendMessage(eventRequest)
    }
}