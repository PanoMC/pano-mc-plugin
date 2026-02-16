package com.panomc.plugins.pano.core.event.listeners

import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.EventHelper

open class OnPlayerPreLogin : Listener {
    override suspend fun handle(eventHelper: EventHelper, vararg args: Any) {}
}