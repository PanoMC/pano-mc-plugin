package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.console.ConsoleStreamer
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.ConsoleStreamMessage

/**
 * Handles `CONSOLE_STREAM`: Pano telling the plugin whether anyone is watching the console.
 */
class ConsoleStreamHandler(
    private val consoleStreamer: ConsoleStreamer
) : PlatformMessageHandler<ConsoleStreamMessage>() {
    override suspend fun handle(response: ConsoleStreamMessage) {
        consoleStreamer.setStreaming(response.enabled)
    }
}
