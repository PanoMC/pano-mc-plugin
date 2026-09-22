package com.panomc.plugins.pano.core.config

import io.vertx.core.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** A config.conf written at version 6 without the heartbeat keys loads with the shipped defaults. */
class ConfigHeartbeatDefaultsTest {
    @Test
    fun `missing heartbeat keys are filled with the defaults`() {
        val filled = ConfigManager.withDefaultHeartbeat(JsonObject().put("config-version", 6))

        assertEquals(PanoConfig.DEFAULT_HEARTBEAT_INTERVAL_SECONDS, filled.getInteger("heartbeat-interval"))
        assertEquals(PanoConfig.DEFAULT_HEARTBEAT_TIMEOUT_SECONDS, filled.getInteger("heartbeat-timeout"))
    }

    @Test
    fun `values the operator set are kept, even invalid ones`() {
        val filled = ConfigManager.withDefaultHeartbeat(
            JsonObject().put("heartbeat-interval", 5).put("heartbeat-timeout", 10)
        )

        assertEquals(5, filled.getInteger("heartbeat-interval"))
        assertEquals(10, filled.getInteger("heartbeat-timeout"))
    }
}
