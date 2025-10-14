package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.ServerData
import org.bukkit.plugin.java.JavaPlugin
import java.net.InetAddress

class SpigotServerData(private val plugin: JavaPlugin) : ServerData {
    override fun serverName(): String = plugin.server.name

    override fun hostAddress(): String = plugin.server.ip.ifBlank { InetAddress.getLocalHost().hostAddress }

    override fun motd(): String = plugin.server.motd

    override fun port(): Int = plugin.server.port

    override fun serverType(): ServerType = SpigotServerUtil.detectServerType()

    override fun serverVersion(): String = plugin.server.version

    override fun playerCount(): Int = plugin.server.onlinePlayers.size

    override fun maxPlayerCount(): Int = plugin.server.maxPlayers
}