package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * `PLAYER_ACTION` - Pano asks for something to be done to one online player.
 *
 * Only the actions the plugin performs through its own API travel this way: `KICK` (with [text]
 * as the optional reason) and `MESSAGE` (with [text] as the chat line). Op, gamemode and the
 * whitelist are composed into console commands on the Pano side and arrive as `EXECUTE_COMMAND`.
 */
data class PlayerActionMessage(
    val action: String,
    val uuid: String,
    val username: String,
    val text: String? = null,
    val issuedBy: String? = null
) : PlatformMessage
