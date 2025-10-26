package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.platform.PlatformRequest

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
    val motd: String?
) : PlatformRequest()