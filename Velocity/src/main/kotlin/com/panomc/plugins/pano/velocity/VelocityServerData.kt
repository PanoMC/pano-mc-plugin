package com.panomc.plugins.pano.velocity

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.ServerData
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class VelocityServerData(private val server: ProxyServer) : ServerData {
    override fun serverName(): String = "Velocity"

    override fun hostAddress(): String = server.boundAddress.hostString

    // Plain/legacy text, matching Spigot's server.motd and Bungee's ListenerInfo.motd — not the
    // raw Adventure JSON component blob GsonComponentSerializer would produce.
    override fun motd(): String = LegacyComponentSerializer.legacySection().serialize(server.configuration.motd)

    override fun port(): Int = server.boundAddress.port

    override fun serverType(): ServerType = ServerType.VELOCITY

    override fun serverVersion(): String = "${server.version.name},${server.version.vendor},${server.version.version}"

    override fun playerCount(): Int = server.playerCount

    override fun maxPlayerCount(): Int = server.configuration.showMaxPlayers

    override fun favicon(): BufferedImage? {
        val iconFile = File("server-icon.png")
        return if (iconFile.exists()) {
            try {
                ImageIO.read(iconFile)
            } catch (e: Exception) {
                null
            }
        } else null
    }
}