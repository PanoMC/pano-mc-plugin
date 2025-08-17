package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.ServerData
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.net.InetAddress
import java.util.Properties

/**
 * Fabric implementation of [ServerData].
 *
 * The Minecraft server instance is captured at runtime via Fabric's server
 * lifecycle callback and stored locally so that server information can be
 * queried reflectively without compile-time dependencies on Minecraft
 * classes. Basic configuration such as host, port, motd and max player count
 * are pulled from the standard `server.properties` file.
 */
class FabricServerData : ServerData {
    @Volatile
    private var server: Any? = null

    fun bindServer(server: Any) {
        this.server = server
    }

    override fun serverName(): String = "Fabric"

    private fun loadProperties(): Properties {
        val props = Properties()
        runCatching {
            File("server.properties").inputStream().use { props.load(it) }
        }
        return props
    }

    override fun hostAddress(): String {
        val props = loadProperties()
        val ip = props.getProperty("server-ip")
        return ip?.takeIf { it.isNotBlank() } ?: InetAddress.getLocalHost().hostAddress
    }

    override fun motd(): String? = loadProperties().getProperty("motd")

    override fun port(): Int = loadProperties().getProperty("server-port")?.toIntOrNull() ?: 0

    override fun serverType(): ServerType = ServerType.FABRIC

    override fun serverVersion(): String {
        return runCatching {
            FabricLoader.getInstance().getModContainer("minecraft").get().metadata.version.friendlyString
        }.getOrElse { "Unknown" }
    }

    override fun playerCount(): Int {
        val srv = server ?: return 0
        return runCatching {
            val playerManager = srv.javaClass.getMethod("getPlayerManager").invoke(srv)
            // try direct count method first
            playerManager.javaClass.methods.firstOrNull { m ->
                m.parameterCount == 0 && m.returnType == Int::class.javaPrimitiveType &&
                    m.name.contains("getCurrentPlayerCount")
            }?.invoke(playerManager) as? Int ?: run {
                val listMethod = playerManager.javaClass.methods.firstOrNull { m ->
                    m.parameterCount == 0 && List::class.java.isAssignableFrom(m.returnType)
                }
                val list = listMethod?.invoke(playerManager) as? List<*>
                list?.size ?: 0
            }
        }.getOrDefault(0)
    }

    override fun maxPlayerCount(): Int {
        val srv = server ?: return 0
        return runCatching {
            val playerManager = srv.javaClass.getMethod("getPlayerManager").invoke(srv)
            val method = playerManager.javaClass.methods.firstOrNull { m ->
                m.parameterCount == 0 && m.returnType == Int::class.javaPrimitiveType &&
                    (m.name.contains("getMaxPlayerCount") ||
                        m.name.contains("getMaxPlayers") ||
                        m.name.contains("getMaxPlayer"))
            }
            (method?.invoke(playerManager) as? Int)
                ?: loadProperties().getProperty("max-players")?.toIntOrNull()
                ?: 0
        }.getOrDefault(0)
    }
}
