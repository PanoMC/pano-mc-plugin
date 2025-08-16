package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.ServerData
import net.minecraft.server.MinecraftServer
import java.net.InetAddress

class FabricServerData(private val server: MinecraftServer) : ServerData {
    override fun serverName(): String = "Fabric"

    override fun hostAddress(): String =
        if (server.serverIp.isNullOrBlank()) InetAddress.getLocalHost().hostAddress else server.serverIp

    override fun motd(): String = server.serverMotd

    override fun port(): Int = server.serverPort

    override fun serverType(): ServerType = ServerType.FABRIC

    override fun serverVersion(): String = server.version

    override fun playerCount(): Int = server.playerManager.playerList.size

    override fun maxPlayerCount(): Int = server.playerManager.maxPlayerCount
}
