package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * `POWER` - Pano asking the server to stop or restart itself.
 *
 * [action] is `STOP` or `RESTART`; anything else is ignored rather than guessed at. There is no
 * acknowledgement: Pano notices the outcome through the connection dropping.
 */
data class PowerMessage(
    val action: String? = null,
    val requestId: String? = null,
    val issuedBy: String? = null
) : PlatformMessage
