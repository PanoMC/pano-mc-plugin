package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * `PANO_PLUGIN_UPDATE` - replace this plugin with a newer build (AGENT.md B3).
 *
 * Only sent to a plugin that announced `self-update`. [url] is normally a path
 * (`/api/server/pano-plugin/jar`) to resolve against the Pano this server is connected to;
 * [sha256] and [size] are what the downloaded bytes have to match before anything is staged;
 * [version] is the build being installed (`local-build` for a development jar) and comes back as
 * `stagedVersion`. Progress is reported on [taskId] and the outcome as `PANO_PLUGIN_UPDATE_RESULT`
 * carrying [eventId].
 *
 * Everything nullable, like every message Pano sends: a newer Pano may drop a field, and a message
 * that does not decode must fail its task rather than the socket.
 */
data class PanoPluginUpdateMessage(
    val eventId: String? = null,
    val taskId: String? = null,
    val url: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
    val fileName: String? = null,
    val version: String? = null
) : PlatformMessage
