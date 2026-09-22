package com.panomc.plugins.pano.core.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.plugins.pano.core.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration5To6 : ConfigMigration(5, 6, "Add console capture switch") {
    override fun migrate(config: JsonObject) {
        config.put("console", JsonObject().put("enabled", true))
    }
}
