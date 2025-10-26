package com.panomc.plugins.pano.core.event.listeners

import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.request.OnPlayerJoinRequest

class OnPlayerJoin(private val platformManager: PlatformManager, private val pluginMain: PanoPluginMain) : Listener {
    override fun handle(eventHelper: EventHelper, vararg args: Any) {
        val player = args[0]
        val playerData = eventHelper.convertToPlayerData(player)

        val eventRequest = OnPlayerJoinRequest(playerData, pluginMain.getServerData().playerCount())

        platformManager.sendMessage(eventRequest)
    }
}