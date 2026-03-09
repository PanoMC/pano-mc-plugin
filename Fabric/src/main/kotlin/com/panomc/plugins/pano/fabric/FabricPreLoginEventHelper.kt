package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.helper.EventHelper
import net.minecraft.text.Text

/**
 * EventHelper implementation for pre-login phase.
 * Instead of disconnecting immediately, stores the disallow state and message
 * so the Mixin can disconnect the player properly via player.networkHandler.disconnect().
 */
class FabricPreLoginEventHelper(
    private val delegate: FabricEventListener
) : EventHelper {

    var wasDisallowed = false
        private set

    var disallowReason: Text? = null
        private set

    override fun sendMessage(commandSender: Any, message: String) {
        delegate.sendMessage(commandSender, message)
    }

    override fun kick(commandSender: Any, message: String) {
        delegate.kick(commandSender, message)
    }

    override fun disallow(event: Any, message: String) {
        wasDisallowed = true
        disallowReason = FabricTextHelper.parseColoredText(message)
    }

    override fun convertToPlayerData(player: Any): EventHelper.Companion.PlayerData {
        return delegate.convertToPlayerData(player)
    }
}
