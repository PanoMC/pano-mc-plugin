package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.PanoPluginUpdateMessage
import com.panomc.plugins.pano.core.update.SelfUpdateService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.logging.Logger

/**
 * Handles `PANO_PLUGIN_UPDATE`: downloads, verifies and stages this plugin's successor.
 *
 * Off the event loop, because a download is blocking I/O that can take minutes on a slow link, and
 * the socket that delivered this message has heartbeats to answer meanwhile. No reply beyond what
 * [SelfUpdateService] reports on the task and in `PANO_PLUGIN_UPDATE_RESULT`.
 */
class PanoPluginUpdateHandler(
    private val selfUpdateService: SelfUpdateService,
    private val logger: Logger
) : PlatformMessageHandler<PanoPluginUpdateMessage>() {
    override suspend fun handle(response: PanoPluginUpdateMessage) {
        try {
            withContext(Dispatchers.IO) {
                selfUpdateService.update(response)
            }
        } catch (exception: Throwable) {
            logger.warning("A Pano update failed: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }
}
