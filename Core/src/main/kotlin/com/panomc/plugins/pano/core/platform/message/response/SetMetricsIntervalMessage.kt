package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * `SET_METRICS_INTERVAL` - Pano asking for `SERVER_METRICS` at a different cadence (AGENT.md
 * 2.4.23), because a panel is watching this server's vitals at [intervalMs].
 *
 * A lease rather than a setting: Pano re-sends it every 60 s while the cadence stays below the
 * ten-second default, and the plugin falls back on its own when that stops. Nullable like every
 * inbound field, so a malformed frame means "back to normal" instead of a crash.
 */
data class SetMetricsIntervalMessage(
    val intervalMs: Long? = null
) : PlatformMessage
