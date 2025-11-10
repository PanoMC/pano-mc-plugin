package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessageResponse

data class GetServerSettingsMessage(
    val authIntegration: Boolean,
    val authRequireVerified: Boolean,
    val authKickAfterRegister: Boolean,
    val banIntegration: Boolean,
    val permissionIntegration: Boolean,
    val translations: Map<String, Map<String, String>>,
    val locale: String,
) : PlatformMessageResponse