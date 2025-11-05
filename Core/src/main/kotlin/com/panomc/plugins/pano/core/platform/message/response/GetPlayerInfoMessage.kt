package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessageResponse

data class GetPlayerInfoMessage(
    val registered: Boolean,
    val banned: Boolean,
    val verified: Boolean,
    val pendingEmail: String? = null,
    val locale: String? = null,
) : PlatformMessageResponse