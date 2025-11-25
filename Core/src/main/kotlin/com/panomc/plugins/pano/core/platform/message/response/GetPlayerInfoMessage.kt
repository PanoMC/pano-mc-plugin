package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessageResponse

data class GetPlayerInfoMessage(
    val registered: Boolean,
    val banned: Boolean,
    val banReason: String? = null,
    val bannedUntil: Long? = null,
    val verified: Boolean,
    val email: String? = null,
    val locale: String? = null,
) : PlatformMessageResponse