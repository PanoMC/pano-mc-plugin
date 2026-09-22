package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.PlayerActionMessage
import java.util.logging.Logger

/**
 * Handles `PLAYER_ACTION`: kicks one player or sends them a chat line from a panel user.
 *
 * Pano has sent this since the players page shipped, but nothing on this side listened for it, so
 * both buttons were silently dropped whenever the plugin (rather than the node) was the source.
 *
 * The message is prefixed with the panel user who wrote it, so a player can tell a message from
 * staff apart from chat. [text] is passed on '&'-coded like every other message the plugin shows,
 * which lets staff colour it; it came from someone with the players permission, not from a player.
 */
class PlayerActionHandler(
    private val pluginMain: PanoPluginMain,
    private val logger: Logger
) : PlatformMessageHandler<PlayerActionMessage>() {
    override suspend fun handle(response: PlayerActionMessage) {
        val text = response.text?.trim()?.takeIf { it.isNotEmpty() }

        try {
            when (response.action.uppercase()) {
                "KICK" -> pluginMain.kickPlayer(response.username, text ?: DEFAULT_KICK_REASON)

                "MESSAGE" -> {
                    if (text == null) {
                        return
                    }

                    val sender = response.issuedBy?.takeIf { it.isNotBlank() } ?: "Pano"
                    val delivered = pluginMain.sendPlayerMessage(
                        response.uuid,
                        response.username,
                        "&8[&bPano&8] &7$sender&8: &f$text"
                    )

                    if (!delivered) {
                        logger.fine("Message from Pano for ${response.username} not delivered: player is not online.")
                    }
                }

                else -> logger.fine("Ignoring unknown player action from Pano: ${response.action}")
            }
        } catch (exception: Throwable) {
            logger.warning("Failed to perform player action ${response.action} for ${response.username}: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    companion object {
        /** Vanilla's own wording for `/kick <player>` with no reason. */
        const val DEFAULT_KICK_REASON = "Kicked by an operator"
    }
}
