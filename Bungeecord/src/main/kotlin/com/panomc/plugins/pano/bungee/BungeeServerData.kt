package com.panomc.plugins.pano.bungee

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.ServerData
import net.md_5.bungee.api.config.ListenerInfo
import net.md_5.bungee.api.plugin.Plugin
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class BungeeServerData(private val plugin: Plugin) : ServerData {
    override fun serverName(): String = plugin.proxy.name

    override fun hostAddress(): String = (plugin.proxy.config.listeners.toList()[0] as ListenerInfo).host.hostString

    override fun motd(): String = (plugin.proxy.config.listeners.toList()[0] as ListenerInfo).motd

    override fun port(): Int = (plugin.proxy.config.listeners.toList()[0] as ListenerInfo).host.port

    override fun serverType(): ServerType = ServerType.BUNGEECORD

    override fun serverVersion(): String = plugin.proxy.version

    override fun playerCount(): Int = plugin.proxy.players.size

    // playerLimit is BungeeCord's optional global hard cap (-1 by default, unset in most configs);
    // the slot count BungeeCord actually advertises on the ping page is the listener's max_players.
    override fun maxPlayerCount(): Int = plugin.proxy.config.listeners.firstOrNull()?.maxPlayers ?: plugin.proxy.config.playerLimit

    override fun favicon(): BufferedImage? {
        val iconFile = File(plugin.dataFolder.parentFile.parentFile, "server-icon.png")
        return if (iconFile.exists()) {
            try {
                ImageIO.read(iconFile)
            } catch (e: Exception) {
                null
            }
        } else null
    }
}