package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * `CONSOLE_STREAM` - Pano turning the live console stream on or off for this server.
 *
 * Sent with `enabled = true` when the first panel subscriber appears and `false` a while after
 * the last one leaves. The plugin starts every connection with streaming off, so nothing is sent
 * until a human is actually watching.
 */
data class ConsoleStreamMessage(
    val enabled: Boolean = false
) : PlatformMessage
