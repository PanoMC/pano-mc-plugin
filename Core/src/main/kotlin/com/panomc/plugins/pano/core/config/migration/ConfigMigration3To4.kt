package com.panomc.plugins.pano.core.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.plugins.pano.core.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration3To4 : ConfigMigration(3, 4, "Add ssl to platform") {
    override fun migrate(config: JsonObject) {
        val platform = config.getJsonObject("platform") ?: return
        platform.put("ssl", false)
    }
}
