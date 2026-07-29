package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.event.listeners.OnPlayerDisconnect
import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.event.listeners.OnPlayerPreLogin
import com.panomc.plugins.pano.core.helper.EventHelper
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class SpigotEventListener(
    private val pluginMain: SpigotMain,
    private val eventHelper: EventHelper,
    // Read from the Bukkit main thread (join/quit) and the async login thread
    // (AsyncPlayerPreLoginEvent) while registerEventListeners/unregisterEventListeners mutate it
    // from the Vert.x event loop — a plain LinkedHashSet would risk ConcurrentModificationException
    // and missed-visibility of newly registered listeners.
    internal val listeners: MutableSet<com.panomc.plugins.pano.core.event.Listener>
) : Listener {

    // AsyncPlayerPreLoginEvent already runs off the main thread (its own per-connection thread),
    // so runBlocking here only waits on that connection — the server isn't stalled. withTimeout
    // still bounds the wait, and the catch blocks fail closed (platform-modules parity with
    // Velocity/Bungee/Fabric): a throwing listener must not leave the event's default ALLOWED
    // result standing, since sendMessageAwaitResponse throws on a null/dropped socket.
    @EventHandler
    fun onPlayerPreLogin(event: AsyncPlayerPreLoginEvent) {
        val preLoginListeners = listeners.filterIsInstance<OnPlayerPreLogin>()

        if (preLoginListeners.isEmpty()) {
            return
        }

        runBlocking {
            try {
                withTimeout(5_000) {
                    preLoginListeners.forEach { it.handle(eventHelper, event, event.name) }
                }
            } catch (e: TimeoutCancellationException) {
                // Platform didn't answer in time: treat it as unavailable rather than hang the
                // login. Deliberately a no-op — if an earlier listener already recorded a disallow
                // on the event before the deadline, that must survive; only the still-pending
                // check is abandoned here.
            } catch (e: Exception) {
                pluginMain.getPanoLogger().severe("Pre-login listener failed for ${event.name}: $e")

                // Only overwrite with a generic message when no listener already recorded a more
                // specific disallow (e.g. BanIntegration's ban reason) — the event's default
                // result is ALLOWED, so that's the signal nothing has disallowed it yet.
                if (event.loginResult == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                    event.disallow(
                        AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        pluginMain.translateColor("An error occurred while verifying your login. Please try again shortly.")
                    )
                }
            }
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        runBlocking {
            listeners.filterIsInstance<OnPlayerJoin>().forEach { it.handle(eventHelper, event.player) }
        }
    }

    @EventHandler
    fun onPlayerDisconnect(event: PlayerQuitEvent) {
        runBlocking {
            listeners.filterIsInstance<OnPlayerDisconnect>().forEach { it.handle(eventHelper, event.player) }
        }
    }
}