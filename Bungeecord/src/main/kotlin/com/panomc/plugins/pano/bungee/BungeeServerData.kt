package com.panomc.plugins.pano.bungee

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.ServerData
import net.md_5.bungee.api.config.ListenerInfo
import net.md_5.bungee.api.plugin.Plugin

class BungeeServerData(private val plugin: Plugin) : ServerData {
    override fun serverName(): String = plugin.proxy.name

    override fun hostAddress(): String = (plugin.proxy.config.listeners.toList()[0] as ListenerInfo).host.hostString

    override fun motd(): String? = null

    override fun port(): Int = (plugin.proxy.config.listeners.toList()[0] as ListenerInfo).host.port

    override fun serverType(): ServerType = ServerType.BUNGEECORD

    override fun serverVersion(): String = plugin.proxy.version

    override fun playerCount(): Int = plugin.proxy.players.size

    override fun maxPlayerCount(): Int = plugin.proxy.config.playerLimit
}