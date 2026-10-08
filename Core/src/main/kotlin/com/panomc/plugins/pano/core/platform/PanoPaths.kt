package com.panomc.plugins.pano.core.platform

import io.vertx.core.json.JsonObject

/**
 * The five HTTP paths this plugin speaks to a Pano platform (protocol 3, API v1).
 *
 * Nothing falls back to the paths older platforms used (`/api/server/...`): a platform that does not
 * serve these is too old for this plugin and says so with `404`, not with a retry on another path.
 */
object PanoPaths {
    const val SERVER_CONNECT = "/api/v1/server/connect"
    const val SERVER_DISCONNECT = "/api/v1/server/disconnect"
    const val SERVER_CONNECTION = "/api/v1/server/connection"

    /** Prefix of a file transfer; the ticket is appended. */
    const val NODE_TRANSFER = "/api/v1/node/transfer/"

    /** Where Pano serves the plugin's own newer build. Pano also sends it as the `url` of `PANO_PLUGIN_UPDATE`. */
    const val PLUGIN_JAR = "/api/v1/server/pano-plugin/jar"
}

/**
 * Reads the error envelope of Pano's API: `{ "error": { "code", "message"?, "details"?, "fields"? } }`.
 * A success has no `error` key. Anything that is not an object under `error` is not an error answer.
 */
object PanoEnvelope {
    /** The `error.code` of [body], or `null` when [body] is no error answer (or carries no code). */
    fun errorCode(body: JsonObject?): String? {
        val error = body?.getValue("error") as? JsonObject ?: return null

        return error.getString("code")?.takeIf { it.isNotEmpty() }
    }

    /** Whether [body] is an error answer, with or without a usable code. */
    fun isError(body: JsonObject?): Boolean = body?.getValue("error") is JsonObject
}
