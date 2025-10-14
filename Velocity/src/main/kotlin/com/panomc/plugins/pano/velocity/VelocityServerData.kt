package com.panomc.plugins.pano.velocity

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.ServerData
import com.velocitypowered.api.proxy.ProxyServer

class VelocityServerData(private val server: ProxyServer) : ServerData {
    override fun serverName(): String = "Velocity"

    override fun hostAddress(): String = server.boundAddress.hostString

    override fun motd(): String? = null

    override fun port(): Int = server.boundAddress.port

    override fun serverType(): ServerType = ServerType.VELOCITY

    override fun serverVersion(): String = "${server.version.name},${server.version.vendor},${server.version.version}"

    override fun playerCount(): Int = server.playerCount

    override fun maxPlayerCount(): Int = server.configuration.showMaxPlayers
}