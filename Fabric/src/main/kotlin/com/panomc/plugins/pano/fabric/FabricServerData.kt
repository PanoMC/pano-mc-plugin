package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.ServerData
import net.minecraft.server.MinecraftServer

class FabricServerData(private val server: MinecraftServer) : ServerData {
    override fun serverName(): String = "Fabric"

    override fun motd(): String = try {
        server.getMotd()
    } catch (_: Exception) {
        ""
    }

    override fun port(): Int = try {
        server.getPort()
    } catch (_: Exception) {
        25565
    }

    override fun serverType(): ServerType = ServerType.FABRIC

    override fun serverVersion(): String = try {
        server.getServerVersion()
    } catch (_: Exception) {
        "unknown"
    }

    override fun playerCount(): Int {
        return try {
            server.getPlayerList().getPlayerCount()
        } catch (e: Exception) {
            try {
                server.getPlayerCount()
            } catch (e2: Exception) {
                org.slf4j.LoggerFactory.getLogger("Pano").error("Failed to get player count", e2)
                0
            }
        }
    }

    override fun maxPlayerCount(): Int = try {
        server.getPlayerList().getMaxPlayers()
    } catch (_: Exception) {
        20
    }
}
