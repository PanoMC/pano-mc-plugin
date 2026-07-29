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
    // How often a WebSocket ping is sent, and how long without a pong before the connection is
    // treated as dead (platform-core-heartbeat). Flat top-level keys, matching
    // await-pano-connection above, rather than a nested block: these are operator-tunable
    // settings, not connection identity/state like PlatformConfig below.
    @SerializedName("heartbeat-interval") var heartbeatInterval: Int = DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
    @SerializedName("heartbeat-timeout") var heartbeatTimeout: Int = DEFAULT_HEARTBEAT_TIMEOUT_SECONDS,
    var platform: PlatformConfig? = PlatformConfig(),
) {
    companion object {
        private val base64Encoder by lazy { Base64.getEncoder() }
        private val keyPair by lazy { KeyGeneratorUtil.generateKeyPair() }

        // Single source of truth for the heartbeat defaults - shared with ConfigMigration4To5
        // (which stamps these into config.conf when migrating an older config) and with
        // PlatformManager's fallback when an operator sets something nonsensical
        // (platform-core-heartbeat).
        const val DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 25
        const val DEFAULT_HEARTBEAT_TIMEOUT_SECONDS = 75

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
