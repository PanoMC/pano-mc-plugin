package com.panomc.plugins.pano.velocity

import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.event.listeners.OnPlayerDisconnect
import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.event.listeners.OnPlayerPreLogin
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

class VelocityEventListener(
    private val pluginMain: PanoPluginMain,
    internal val listeners: MutableSet<Listener>
) : EventHelper {

    // translateColor() emits console ANSI escapes, not something Component.text() can render for
    // a player: deserialize the raw '&'-coded string into a real Component instead.
    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSource).sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message))
    }

    override fun kick(commandSender: Any, message: String) {
        (commandSender as Player).disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize(message))
    }

    override fun disallow(event: Any, message: String) {
        (event as PreLoginEvent).result = PreLoginEvent.PreLoginComponentResult.denied(LegacyComponentSerializer.legacyAmpersand().deserialize(message))
    }

    override fun convertToPlayerData(player: Any): EventHelper.Companion.PlayerData {
        val playerInstance = player as Player

        return EventHelper.Companion.PlayerData(
            uuid = playerInstance.uniqueId,
            username = playerInstance.username,
            ping = playerInstance.ping,
            ipAddress = playerInstance.remoteAddress.address.hostAddress
        )
    }

    // EventTask.async() hops this off Velocity's Netty event-loop thread before running the
    // Runnable, and Velocity holds this player's login (without blocking that worker) until the
    // Runnable returns. Without this, runBlocking here parked the Netty worker itself, hanging
    // every other connection multiplexed on it whenever the platform accepted the WebSocket but
    // never answered.
    @Subscribe
    fun onPlayerPreLogin(event: PreLoginEvent): EventTask = EventTask.async {
        val preLoginListeners = listeners.filterIsInstance<OnPlayerPreLogin>()

        if (preLoginListeners.isEmpty()) {
            return@async
        }

        runBlocking {
            try {
                withTimeout(5_000) {
                    preLoginListeners.forEach { listener ->
                        listener.handle(this@VelocityEventListener, event, event.username)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // Platform didn't answer in time: treat it as unavailable rather than hang the
                // login. Deliberately a no-op — if an earlier listener already recorded a disallow
                // on the event before the deadline, that must survive; only the still-pending check
                // is abandoned here.
            } catch (e: Exception) {
                // Fail closed: the default PreLoginEvent result is ALLOWED, so a listener that
                // throws (e.g. a missing i18n key in BanIntegration) must not leave the login
                // unresolved — that would let a banned player straight through.
                pluginMain.getPanoLogger().severe("Pre-login listener failed for ${event.username}: $e")

                // Only fall back to the generic message if nothing already denied the login —
                // an earlier listener may have recorded the real ban reason/untilTime before this
                // one threw, and that must not be overwritten.
                if (event.result.isAllowed) {
                    disallow(event, "An error occurred while verifying your login. Please try again shortly.")
                }
            }
        }
    }

    @Subscribe
    fun onPlayerJoin(event: PostLoginEvent) {
        runBlocking {
            listeners.filterIsInstance<OnPlayerJoin>().forEach { it.handle(this@VelocityEventListener, event.player) }
        }
    }

    @Subscribe
    fun onPlayerDisconnect(event: DisconnectEvent) {
        runBlocking {
            listeners.filterIsInstance<OnPlayerDisconnect>().forEach { it.handle(this@VelocityEventListener, event.player) }
        }
    }
}