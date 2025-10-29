package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest

class ChangePasswordRequest(val username: String, val password: String) : PlatformRequest()