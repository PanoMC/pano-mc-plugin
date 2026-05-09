package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessageResponse

enum class GenerateLinkCodeStatus {
    SUCCESS,
    ALREADY_REGISTERED,
    USER_NOT_FOUND
}

data class GenerateLinkCodeMessage(
    val code: String? = null,
    val status: GenerateLinkCodeStatus = GenerateLinkCodeStatus.SUCCESS
) : PlatformMessageResponse
