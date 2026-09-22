package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.platform.PlatformRequest

/**
 * `ON_SERVER_CONNECT` - who this server is, sent once per connection.
 *
 * [timeZone] is the game JVM's own zone id (`Europe/Istanbul`, `UTC`, ...), so the panel can
 * show times the way the server's own log prints them (AGENT.md 2.4.25). Additive on protocol 2
 * and nullable: an older Pano ignores it, and a JVM without a usable zone sends null.
 */
data class OnServerConnectRequest(
    val serverName: String,
    val playerCount: Int,
    val maxPlayerCount: Int,
    val serverType: ServerType,
    val serverVersion: String,
    val host: String,
    val port: Int,
    val startTime: Long,
    val favicon: String?,
    val motd: String?,
    val protocolVersion: Int,
    val pluginVersion: String,
    val capabilities: List<String>,
    val timeZone: String? = null
) : PlatformRequest()