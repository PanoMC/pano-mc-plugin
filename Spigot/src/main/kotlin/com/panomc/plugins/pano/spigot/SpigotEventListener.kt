package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.event.listeners.OnPlayerDisconnect
import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.event.listeners.OnPlayerPreLogin
import com.panomc.plugins.pano.core.helper.EventHelper
import kotlinx.coroutines.runBlocking
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class SpigotEventListener(
    private val eventHelper: EventHelper,
    internal val listeners: MutableSet<com.panomc.plugins.pano.core.event.Listener>
) : Listener {

    @EventHandler
    fun onPlayerPreLogin(event: AsyncPlayerPreLoginEvent) {
        runBlocking {
            listeners.filterIsInstance<OnPlayerPreLogin>().forEach { it.handle(eventHelper, event, event.name) }
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