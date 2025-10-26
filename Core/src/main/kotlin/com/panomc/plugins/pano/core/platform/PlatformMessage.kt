package com.panomc.plugins.pano.core.platform

import com.panomc.plugins.pano.core.util.TextUtil.convertToSnakeCase

interface PlatformMessage {
    companion object {
        fun Class<out PlatformMessage>.responseName() =
            this.simpleName.replace("Message", "").convertToSnakeCase().uppercase()
    }
}