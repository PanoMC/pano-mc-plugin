package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage

class GetServerSettingsHandler(
    private val platformManager: PlatformManager,
    private val pluginMain: PanoPluginMain
) : PlatformMessageHandler<GetServerSettingsMessage>() {
    override suspend fun handle(response: GetServerSettingsMessage) {
        platformManager.serverSettings = response

        pluginMain.getPanoLogger().info(pluginMain.translateColor("&eServer settings changed."))

        pluginMain.onServerSettingsChanged(response)
    }
}