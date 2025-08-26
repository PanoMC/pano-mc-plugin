package com.panomc.plugins.pano.forge

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.ServerData

/**
 * Placeholder server data for Forge servers. Actual values should
 * be provided through Forge server APIs at runtime.
 */
class ForgeServerData : ServerData {
    override fun serverName(): String = "ForgeServer"

    override fun motd(): String? = null

    override fun port(): Int = 0

    override fun serverType(): ServerType = ServerType.FORGE

    override fun serverVersion(): String = "unknown"

    override fun playerCount(): Int = 0

    override fun maxPlayerCount(): Int = 0
}
