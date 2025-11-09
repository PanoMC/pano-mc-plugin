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

        // Update I18nManager cache when server settings change
        platformManager.i18nManager.updateCache(response)

        pluginMain.getPanoLogger().info(pluginMain.translateColor("&eServer settings changed."))

        pluginMain.onServerSettingsChanged(response)
    }
}