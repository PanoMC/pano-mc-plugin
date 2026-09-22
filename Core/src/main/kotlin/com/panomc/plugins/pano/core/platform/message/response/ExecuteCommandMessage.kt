package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * `EXECUTE_COMMAND` - a console command a panel user sent from Pano.
 *
 * There is no acknowledgement message: the command's own output reaches the panel through the
 * console stream, which is also where the echo line naming [issuedBy] shows up.
 */
data class ExecuteCommandMessage(
    val command: String = "",
    val requestId: String? = null,
    val issuedBy: String? = null
) : PlatformMessage
