package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.ServerData
import net.minecraft.server.MinecraftServer

class FabricServerData(private val server: MinecraftServer) : ServerData {
    override fun serverName(): String = "Fabric"

    override fun motd(): String = try {
        server.serverMotd
    } catch (_: Exception) {
        ""
    }

    override fun port(): Int = try {
        server.serverPort
    } catch (_: Exception) {
        25565
    }

    override fun serverType(): ServerType = ServerType.FABRIC

    override fun serverVersion(): String = try {
        server.version
    } catch (_: Exception) {
        "unknown"
    }

    override fun playerCount(): Int {
        return try {
            val count = server.playerManager.playerList.size
            count
        } catch (e: Exception) {
            try {
                val count = server.currentPlayerCount
                count
            } catch (e2: Exception) {
                org.slf4j.LoggerFactory.getLogger("Pano").error("Failed to get player count", e2)
                0
            }
        }
    }

    override fun maxPlayerCount(): Int = try {
        server.playerManager.maxPlayerCount
    } catch (_: Exception) {
        try {
            server.maxPlayerCount
        } catch (_: Exception) {
            20
        }
    }
}
