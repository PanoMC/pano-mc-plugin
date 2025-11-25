package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

data class BanPlayerMessage(
    val username: String,
    val locale: String?,
    val banReason: String? = null,
    val bannedUntil: Long? = null,
) : PlatformMessage