package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * `SET_PLUGIN_ENABLED` - Pano asking for a plugin to be enabled or disabled.
 *
 * Only the Bukkit family acts on it; the other platforms cannot toggle a loaded plugin and ignore
 * the message. The `plugins` capability means "can list", never "can toggle".
 */
data class SetPluginEnabledMessage(
    val name: String = "",
    val enabled: Boolean = false
) : PlatformMessage
