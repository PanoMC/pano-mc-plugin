package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessageResponse

data class PlayerAuthenticateMessage(val success: Boolean) : PlatformMessageResponse