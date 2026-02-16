package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest

data class GenerateLinkCodeRequest(val username: String) : PlatformRequest()
