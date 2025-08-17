package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.ServerData
import java.net.InetAddress

class FabricServerData : ServerData {
    override fun serverName(): String = "Fabric"

    override fun hostAddress(): String = InetAddress.getLocalHost().hostAddress

    override fun motd(): String? = null

    override fun port(): Int = 0

    override fun serverType(): ServerType = ServerType.FABRIC

    override fun serverVersion(): String = "Unknown"

    override fun playerCount(): Int = 0

    override fun maxPlayerCount(): Int = 0
}
