package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.metrics.MetricsReporter
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.SetMetricsIntervalMessage

/**
 * Handles `SET_METRICS_INTERVAL`: reschedules the metrics timer, clamped and leased (see
 * [MetricsReporter.setInterval]).
 */
class SetMetricsIntervalHandler(
    private val metricsReporter: MetricsReporter
) : PlatformMessageHandler<SetMetricsIntervalMessage>() {
    override suspend fun handle(response: SetMetricsIntervalMessage) {
        metricsReporter.setInterval(response.intervalMs)
    }
}
