package com.panomc.plugins.pano.nukkit

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.ServerData
import cn.nukkit.plugin.PluginBase

class NukkitServerData(private val plugin: PluginBase) : ServerData {
    override fun serverName(): String = plugin.server.name

    override fun hostAddress(): String = plugin.server.ip

    override fun motd(): String? = plugin.server.motd

    override fun port(): Int = plugin.server.port

    override fun serverType(): ServerType = ServerType.NUKKIT

    override fun serverVersion(): String = plugin.server.version

    override fun playerCount(): Int = plugin.server.onlinePlayers.size

    override fun maxPlayerCount(): Int = plugin.server.maxPlayers
}
