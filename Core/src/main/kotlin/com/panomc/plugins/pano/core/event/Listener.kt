package com.panomc.plugins.pano.core.event

import com.panomc.plugins.pano.core.helper.EventHelper

interface Listener {
    val eventType: EventType

    fun handle(eventHelper: EventHelper, vararg args: Any)
}