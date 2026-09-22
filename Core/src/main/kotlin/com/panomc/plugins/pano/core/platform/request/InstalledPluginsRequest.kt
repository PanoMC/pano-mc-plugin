package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest
import com.panomc.plugins.pano.core.platform.entity.InstalledPlugin

/**
 * `INSTALLED_PLUGINS` - the full plugin/mod list, pushed fire-and-forget.
 *
 * Always the complete list, never a delta: it is sent right after `ON_SERVER_CONNECT` and again
 * after every `SET_PLUGIN_ENABLED`, and a whole list is both smaller to reason about and
 * impossible to get out of sync.
 */
data class InstalledPluginsRequest(
    val plugins: List<InstalledPlugin>
) : PlatformRequest()
