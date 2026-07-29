package com.panomc.plugins.pano.core.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.plugins.pano.core.config.ConfigMigration
import com.panomc.plugins.pano.core.util.KeyGeneratorUtil
import io.vertx.core.json.JsonObject
import java.util.*

@Migration
class ConfigMigration1To2 : ConfigMigration(1, 2, "Convert access_token to access-token") {
    override fun migrate(config: JsonObject) {
        val keyPair = KeyGeneratorUtil.generateKeyPair()
        val base64Encoder = Base64.getEncoder()

        val publicKey = String(base64Encoder.encode(keyPair.public.encoded))
        val privateKey = String(base64Encoder.encode(keyPair.private.encoded))

        config.put("public-key", publicKey)
        config.put("private-key", privateKey)

        // "platform" is optional / may be missing on a hand-edited config; ConfigMigration3To4
        // guards the same access the same way — mirror it instead of NPE-ing on the `put`.
        val platformConfig = config.getJsonObject("platform") ?: JsonObject().also { config.put("platform", it) }
        platformConfig.put("encryption-key", "")
    }
}