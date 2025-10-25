package com.panomc.plugins.pano.core.event.events

import com.panomc.plugins.pano.core.ServerEventRequest
import com.panomc.plugins.pano.core.helper.EventHelper

data class OnPlayerDisconnectRequest(
    val player: EventHelper.Companion.PlayerData,
    val playerCount: Int
) : ServerEventRequest()