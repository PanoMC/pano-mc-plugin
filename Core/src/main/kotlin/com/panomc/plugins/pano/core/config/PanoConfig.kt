package com.panomc.plugins.pano.core.config

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.panomc.plugins.pano.core.util.KeyGeneratorUtil
import io.vertx.core.json.JsonObject
import java.util.*

data class PanoConfig(
    @SerializedName("config-version") var version: Int,
    @SerializedName("public-key") var publicKey: String = String(base64Encoder.encode(keyPair.public.encoded)),
    @SerializedName("private-key") var privateKey: String = String(base64Encoder.encode(keyPair.private.encoded)),
    @SerializedName("await-pano-connection") var awaitPanoConnection: Boolean = true,
    var platform: PlatformConfig? = PlatformConfig(),
) {
    companion object {
        private val base64Encoder by lazy { Base64.getEncoder() }
        private val keyPair by lazy { KeyGeneratorUtil.generateKeyPair() }

        data class PlatformConfig(
            var host: String = "",
            var port: Int = 8080,
            var ssl: Boolean = false,
            var token: String = "",
            @SerializedName("encryption-key") var encryptionKey: String = ""
        )

        private val gson = GsonBuilder()
            .create()

        fun from(jsonObject: JsonObject) = gson.fromJson(jsonObject.encode(), PanoConfig::class.java)
    }

    override fun toString(): String = gson.toJson(this)
}
