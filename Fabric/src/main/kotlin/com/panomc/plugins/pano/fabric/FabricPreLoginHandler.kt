package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.event.listeners.OnPlayerPreLogin
import kotlinx.coroutines.runBlocking
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text

/**
 * Handles pre-login checks (ban integration) before the player enters the game world.
 * Called from the PlayerManagerMixin before onPlayerConnect proceeds.
 *
 * Returns the disconnect reason Text if the player should be denied, or null to allow.
 */
object FabricPreLoginHandler {

    internal var fabricMain: FabricMain? = null

    /**
     * @return disconnect reason Text if the player was denied entry (e.g. banned), null to allow
     */
    fun handlePreLogin(player: ServerPlayerEntity): Text? {
        val main = fabricMain ?: return null

        val eventListener = main.getFabricEventListenerOrNull() ?: return null
        val preLoginListeners = eventListener.listeners.filterIsInstance<OnPlayerPreLogin>()

        if (preLoginListeners.isEmpty()) {
            return null
        }

        val username = try {
            val profile = player.gameProfile
            try {
                profile.javaClass.getMethod("name").invoke(profile) as String
            } catch (_: NoSuchMethodException) {
                profile.javaClass.getMethod("getName").invoke(profile) as String
            }
        } catch (_: Exception) {
            player.name.string
        }

        val preLoginHelper = FabricPreLoginEventHelper(eventListener)

        runBlocking {
            try {
                preLoginListeners.forEach {
                    it.handle(preLoginHelper, player, username)
                }
            } catch (e: Exception) {
                org.slf4j.LoggerFactory.getLogger("Pano").error("Error handling player pre-login", e)
            }
        }

        return if (preLoginHelper.wasDisallowed) preLoginHelper.disallowReason else null
    }
}
