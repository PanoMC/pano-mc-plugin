package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest
import java.util.UUID

/**
 * `PANO_PLUGIN_UPDATE_RESULT` - how a `PANO_PLUGIN_UPDATE` ended (AGENT.md B3).
 *
 * Carries the id of the push it answers. [stagedVersion] is the build now waiting for the restart;
 * [mode] is how it gets applied: `update-folder` (Bukkit's `plugins/update/` at the next boot),
 * `swap-on-shutdown` (this plugin replaces its jar as the server stops) or `up-to-date` (nothing
 * was staged, the running jar already is that build). On failure [error] is one of the
 * `PanoSelfUpdate.ERROR_*` codes or the exception's message.
 */
class PanoPluginUpdateResultRequest(
    eventId: UUID,
    val taskId: String,
    val ok: Boolean,
    val error: String?,
    val stagedVersion: String?,
    val mode: String?
) : PlatformRequest(eventId)
