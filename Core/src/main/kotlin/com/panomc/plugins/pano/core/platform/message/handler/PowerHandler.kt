package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.PowerMessage
import java.util.logging.Logger

/**
 * Handles `POWER`: stops or restarts the server on Pano's request.
 *
 * Who asked is written to the server's own log before anything happens, so the last line before a
 * remote shutdown always names the person responsible - the server console is the one record that
 * survives even when the machine never comes back and Pano's activity log is out of reach.
 */
class PowerHandler(
    private val pluginMain: PanoPluginMain,
    private val logger: Logger
) : PlatformMessageHandler<PowerMessage>() {
    override suspend fun handle(response: PowerMessage) {
        val issuedBy = response.issuedBy?.takeIf { it.isNotBlank() } ?: "unknown"

        when (response.action?.trim()?.uppercase()) {
            "STOP" -> {
                logger.warning("Stopping the server: requested from Pano by $issuedBy.")

                runSafely("stop") { pluginMain.shutdown() }
            }

            "RESTART" -> {
                logger.warning("Restarting the server: requested from Pano by $issuedBy.")

                runSafely("restart") { pluginMain.restart() }
            }

            else -> logger.warning("Ignoring a POWER request from Pano with an unknown action: ${response.action}")
        }
    }

    private inline fun runSafely(action: String, block: () -> Unit) {
        try {
            block()
        } catch (exception: Throwable) {
            logger.severe("Failed to $action the server on Pano's request: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }
}
