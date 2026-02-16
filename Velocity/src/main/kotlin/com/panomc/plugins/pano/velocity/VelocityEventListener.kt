package com.panomc.plugins.pano.velocity

import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.event.listeners.OnPlayerDisconnect
import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.event.listeners.OnPlayerPreLogin
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component

class VelocityEventListener(
    private val pluginMain: PanoPluginMain,
    internal val listeners: MutableSet<Listener>
) : EventHelper {

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSource).sendMessage(Component.text(pluginMain.translateColor(message)))
    }

    override fun kick(commandSender: Any, message: String) {
        (commandSender as Player).disconnect(Component.text(pluginMain.translateColor(message)))
    }

    override fun disallow(event: Any, message: String) {
        (event as PreLoginEvent).result = PreLoginEvent.PreLoginComponentResult.denied(Component.text(pluginMain.translateColor(message)))
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

    @Subscribe
    fun onPlayerPreLogin(event: PreLoginEvent) {
        runBlocking {
            listeners.filterIsInstance<OnPlayerPreLogin>().forEach { it.handle(this@VelocityEventListener, event, event.username) }
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