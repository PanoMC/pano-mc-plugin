package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest
import com.panomc.plugins.pano.core.platform.entity.PlayerData

/**
 * `SERVER_METRICS` - a periodic health sample, pushed fire-and-forget every ten seconds while the
 * connection is up.
 *
 * Not gated by panel subscribers on purpose: Pano rolls these samples into the `server_metric`
 * table once a minute, so the graphs have history even for a server nobody was watching.
 *
 * [tps] is `[1m, 5m, 15m]` and, like [mspt], is null wherever the concept does not exist - both
 * proxies always, and a plain Spigot server for [mspt] specifically (see `SpigotMetrics`).
 * [cpu] is this JVM's process CPU as a percentage, null when the JVM does not expose it.
 * [diskUsed] is the server directory's size in bytes (AGENT.md 2.4.18 A), null until the first
 * measurement has finished - it is far too slow to take inline, see `DiskUsageProbe`. [diskTotal]
 * is the size of the whole partition that directory sits on, which is a single cheap syscall and
 * so is taken inline on every sample; it is what turns [diskUsed] into the panel's disk gauge.
 * Both are additive on protocol 2: an older Pano simply ignores the fields.
 */
data class ServerMetricsRequest(
    val t: Long,
    val tps: List<Double>?,
    val mspt: Double?,
    val memUsed: Long,
    val memMax: Long,
    val cpu: Double?,
    val playerCount: Int,
    val maxPlayerCount: Int,
    val players: List<PlayerData>,
    val diskUsed: Long? = null,
    val diskTotal: Long? = null
) : PlatformRequest()
