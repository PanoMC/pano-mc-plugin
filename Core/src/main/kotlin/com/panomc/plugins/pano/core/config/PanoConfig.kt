package com.panomc.plugins.pano.core.config

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import io.vertx.core.json.JsonObject

data class PanoConfig(
    @SerializedName("config-version") var version: Int,
    var platform: PlatformConfig? = PlatformConfig(),
) {
    companion object {
        data class PlatformConfig(
            var host: String? = "",
            var port: Int? = 8080,
            var token: String? = ""
        )

        private val gson = GsonBuilder()
            .create()

        fun from(jsonObject: JsonObject) = gson.fromJson(jsonObject.encode(), PanoConfig::class.java)
    }

    override fun toString(): String = gson.toJson(this)
}
