package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.platform.PlatformRequest

data class OnPlayerJoinRequest(
    val player: EventHelper.Companion.PlayerData,
    val playerCount: Int
) : PlatformRequest()