package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest

class PlayerAuthenticateRequest(val username: String, val password: String) : PlatformRequest()