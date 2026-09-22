package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.SetPluginEnabledMessage
import java.util.logging.Logger

/**
 * Handles `SET_PLUGIN_ENABLED`: toggles a plugin and reports the resulting list back.
 *
 * The refreshed `INSTALLED_PLUGINS` is sent after a short delay rather than immediately: the
 * toggle itself is queued onto the server's main thread, so reading the list straight away would
 * report the state from before it ran.
 */
class SetPluginEnabledHandler(
    private val platformManager: PlatformManager,
    private val pluginMain: PanoPluginMain,
    private val logger: Logger
) : PlatformMessageHandler<SetPluginEnabledMessage>() {
    companion object {
        private const val REFRESH_DELAY_MILLIS = 500L
    }

    override suspend fun handle(response: SetPluginEnabledMessage) {
        val name = response.name.trim()

        if (name.isEmpty()) {
            return
        }

        val accepted = try {
            pluginMain.setPluginEnabled(name, response.enabled)
        } catch (exception: Throwable) {
            logger.warning("Failed to ${if (response.enabled) "enable" else "disable"} plugin \"$name\": ${exception.javaClass.simpleName}: ${exception.message}")

            false
        }

        if (!accepted) {
            // Still report: Pano asked for a change it did not get, and the only way it learns
            // that is by seeing the unchanged list.
            logger.warning("Ignored a request from Pano to ${if (response.enabled) "enable" else "disable"} plugin \"$name\".")
        }

        platformManager.sendInstalledPlugins(REFRESH_DELAY_MILLIS)
    }
}
