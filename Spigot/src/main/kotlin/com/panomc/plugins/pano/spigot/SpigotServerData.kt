package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.ServerData
import org.bukkit.plugin.java.JavaPlugin
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import javax.imageio.ImageIO

class SpigotServerData(private val plugin: JavaPlugin) : ServerData {
    override fun serverName(): String = plugin.server.name

    // server-ip is blank in a default server.properties, so getLocalHost() is the normal path,
    // not a rare fallback — it does a name-service lookup and throws when the machine's hostname
    // is not resolvable (some minimal/hand-built images). Fall back to loopback rather than
    // letting that throw escape into the connect/status-report callers.
    override fun hostAddress(): String = plugin.server.ip.ifBlank {
        try {
            InetAddress.getLocalHost().hostAddress
        } catch (_: UnknownHostException) {
            "127.0.0.1"
        }
    }

    override fun motd(): String = plugin.server.motd

    override fun port(): Int = plugin.server.port

    override fun serverType(): ServerType = SpigotServerUtil.detectServerType()

    override fun serverVersion(): String = plugin.server.version

    override fun playerCount(): Int = plugin.server.onlinePlayers.size

    override fun maxPlayerCount(): Int = plugin.server.maxPlayers

    override fun favicon(): BufferedImage? {
        val iconFile = File(plugin.dataFolder.parentFile.parentFile, "server-icon.png")
        return if (iconFile.exists()) {
            try {
                ImageIO.read(iconFile)
            } catch (e: Exception) {
                null
            }
        } else null
    }
}