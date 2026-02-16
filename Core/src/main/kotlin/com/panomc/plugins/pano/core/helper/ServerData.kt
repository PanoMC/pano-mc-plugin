package com.panomc.plugins.pano.core.helper

import com.panomc.plugins.pano.core.ServerType
import java.awt.image.BufferedImage
import java.net.InetAddress

interface ServerData {
    fun serverName(): String

    fun hostAddress(): String = InetAddress.getLocalHost().hostAddress

    fun motd(): String?

    fun port(): Int

    fun serverType(): ServerType

    fun serverVersion(): String

    fun playerCount(): Int

    fun maxPlayerCount(): Int

    fun favicon(): BufferedImage? = null

    fun connectableHostAddress(): String {
        val host = hostAddress()
        return if (host == "0.0.0.0") "127.0.0.1" else host
    }
}