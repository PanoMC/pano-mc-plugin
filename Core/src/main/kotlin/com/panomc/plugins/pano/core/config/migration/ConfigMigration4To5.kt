package com.panomc.plugins.pano.core.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.plugins.pano.core.config.ConfigMigration
import com.panomc.plugins.pano.core.config.PanoConfig
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration4To5 : ConfigMigration(4, 5, "Add platform connection heartbeat") {
    override fun migrate(config: JsonObject) {
        config.put("heartbeat-interval", PanoConfig.DEFAULT_HEARTBEAT_INTERVAL_SECONDS)
        config.put("heartbeat-timeout", PanoConfig.DEFAULT_HEARTBEAT_TIMEOUT_SECONDS)
    }
}
