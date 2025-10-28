package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessageResponse

data class GetServerSettingsMessage(
    val authIntegration: Boolean,
    val banIntegration: Boolean,
    val permissionIntegration: Boolean
) : PlatformMessageResponse