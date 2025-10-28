package com.panomc.plugins.pano.core.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.plugins.pano.core.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration2To3 : ConfigMigration(2, 3, "Add await pano connection") {
    override fun migrate(config: JsonObject) {
        config.put("await-pano-connection", true)
    }
}