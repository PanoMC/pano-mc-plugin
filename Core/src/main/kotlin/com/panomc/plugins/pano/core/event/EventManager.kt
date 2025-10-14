package com.panomc.plugins.pano.core.event

import com.panomc.plugins.pano.core.PlatformManager
import com.panomc.plugins.pano.core.event.listeners.OnPlayerDisconnect
import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.helper.PanoPluginMain

class EventManager(private val panoPluginMain: PanoPluginMain, platformManager: PlatformManager) {
    private val eventListeners = listOf(
        OnPlayerJoin(platformManager, panoPluginMain),
        OnPlayerDisconnect(platformManager, panoPluginMain)
    )

    fun init() {
        panoPluginMain.registerEventListeners(eventListeners)
    }

    fun disable() {
        panoPluginMain.unregisterEventListeners(eventListeners)
    }
}