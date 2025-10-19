package com.panomc.plugins.pano.core.config

import io.vertx.core.json.JsonObject

abstract class ConfigMigration(
    val from: Int,
    val to: Int,
    val versionInfo: String
) {
    fun isMigratable(version: Int) = version == from

    abstract fun migrate(config: JsonObject)
}