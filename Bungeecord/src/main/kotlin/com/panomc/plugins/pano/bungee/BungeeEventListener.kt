package com.panomc.plugins.pano.bungee

import com.panomc.plugins.pano.core.event.listeners.OnPlayerDisconnect
import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.event.listeners.OnPlayerPreLogin
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.runBlocking
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.PreLoginEvent
import net.md_5.bungee.api.plugin.Listener
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
        runBlocking {
            listeners.filterIsInstance<OnPlayerPreLogin>().forEach { it.handle(this@BungeeEventListener, event, event.connection.name) }
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