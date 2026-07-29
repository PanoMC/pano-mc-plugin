package com.panomc.plugins.pano.bungee

import com.panomc.plugins.pano.core.event.listeners.OnPlayerDisconnect
import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.event.listeners.OnPlayerPreLogin
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.PreLoginEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.event.EventHandler
import java.net.InetSocketAddress


class BungeeEventListener(
    private val pluginMain: PanoPluginMain,
    internal val listeners: MutableSet<com.panomc.plugins.pano.core.event.Listener>
) : Listener, EventHelper {

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSender).sendMessage(TextComponent(pluginMain.translateColor(message)))
    }

    override fun kick(commandSender: Any, message: String) {
        (commandSender as ProxiedPlayer).disconnect(TextComponent(pluginMain.translateColor(message)))
    }

    override fun disallow(event: Any, message: String) {
        val preLoginEvent = (event as PreLoginEvent)
        preLoginEvent.isCancelled = true
        preLoginEvent.reason = TextComponent( pluginMain.translateColor(message))
    }

    override fun convertToPlayerData(player: Any): EventHelper.Companion.PlayerData {
        val playerInstance = player as ProxiedPlayer

        return EventHelper.Companion.PlayerData(
            uuid = playerInstance.uniqueId,
            username = playerInstance.name,
            ping = playerInstance.ping.toLong(),
            ipAddress = (playerInstance.socketAddress as InetSocketAddress).address.hostAddress
        )
    }

    @EventHandler
    fun onPreLogin(event: PreLoginEvent) {
        val preLoginListeners = listeners.filterIsInstance<OnPlayerPreLogin>()

        if (preLoginListeners.isEmpty()) {
            return
        }

        // InitialHandler.handle(LoginRequest) fires PreLoginEvent synchronously on the connection's
        // Netty event-loop thread. registerIntent/completeIntent hold up this player's login without
        // parking that worker, so every other connection multiplexed on it keeps flowing.
        val plugin = pluginMain as Plugin
        val bungeeMain = pluginMain as BungeeMain

        // Read the scope, guarded, *before* registering the intent: coroutineScope is a lateinit var
        // assigned in onEnable(), so reading it before that (or after some other startup failure left
        // this instance without one) throws UninitializedPropertyAccessException. (Kotlin only allows
        // `::coroutineScope.isInitialized` from inside BungeeMain itself, not from this class, so the
        // check has to be this try/catch instead.) Either way, skip the check entirely rather than
        // register an intent nothing will ever complete — the player is allowed through rather than
        // held until Bungee's own timeout.
        val scope = try {
            bungeeMain.coroutineScope
        } catch (_: UninitializedPropertyAccessException) {
            return
        }

        // Reuse BungeeMain's long-lived scope (created in onEnable, cancelled in onDisable) instead
        // of a throwaway CoroutineScope: otherwise nothing cancels this on plugin shutdown and a
        // login check can outlive the plugin and touch a torn-down Pano. CoroutineStart.ATOMIC
        // guarantees the block below — and therefore its `finally { completeIntent }` — always runs
        // even if the scope's Job is already cancelled by the time this launch() executes (onDisable
        // ran mid-login): with the default start, launching on an already-cancelled Job skips the
        // body entirely, which would leave the registerIntent() below with no matching completeIntent
        // and hang the login until Bungee's own timeout.
        event.registerIntent(plugin)

        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                withTimeout(5_000) {
                    preLoginListeners.forEach { it.handle(this@BungeeEventListener, event, event.connection.name) }
                }
            } catch (e: TimeoutCancellationException) {
                // Platform didn't answer in time: treat it as unavailable rather than hang the login.
            } catch (e: CancellationException) {
                // The scope's Job was cancelled out from under this launch (BungeeMain.onDisable()
                // running mid-check, e.g. a plugin/proxy shutdown): per the owner's pre-login policy,
                // "plugin shutting down mid-check" must ALLOW like a disconnected platform, not DENY.
                // TimeoutCancellationException is caught above first since it's a CancellationException
                // subtype and needs its own comment, but behaves identically here — do nothing, so the
                // player isn't disallowed. An earlier listener's disallow() (e.g. a real ban) is
                // untouched and still wins, matching "a recorded disallow still wins".
            } catch (e: Exception) {
                // A broken check must not let a banned player slip through: fail closed. But only
                // set the generic reason when nothing has been recorded yet — an earlier listener
                // may have already disallowed with the real ban reason and its untilTime, which
                // this must not overwrite.
                pluginMain.getPanoLogger().warning("Pre-login check failed for ${event.connection.name}: ${e.message}")
                if (!event.isCancelled) {
                    disallow(event, "Unable to verify your account right now, please try again shortly.")
                }
            } finally {
                event.completeIntent(plugin)
            }
        }
    }

    @EventHandler
    fun onPostLogin(event: PostLoginEvent) {
        runBlocking {
            listeners.filterIsInstance<OnPlayerJoin>().forEach { it.handle(this@BungeeEventListener, event.player) }
        }
    }

    @EventHandler
    fun onPlayerDisconnect(event: PlayerDisconnectEvent) {
        runBlocking {
            listeners.filterIsInstance<OnPlayerDisconnect>().forEach { it.handle(this@BungeeEventListener, event.player) }
        }
    }
}