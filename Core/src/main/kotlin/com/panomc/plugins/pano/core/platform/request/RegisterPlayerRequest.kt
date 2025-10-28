package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest

class RegisterPlayerRequest(val username: String, val password: String, val ipAddress: String) : PlatformRequest()