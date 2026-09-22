package com.panomc.plugins.pano.core.platform

import com.panomc.plugins.pano.core.util.TextUtil.convertToSnakeCase
import io.vertx.core.json.JsonObject
import java.util.*

/**
 * One message the plugin sends to Pano.
 *
 * [eventId] is the correlation id carried by every message in both directions. A request the
 * plugin starts gets a fresh one, which Pano echoes back on its answer (see
 * `PlatformManager.sendMessageAwaitResponse`). A message that *answers* something Pano asked -
 * `CONSOLE_HISTORY_RESULT` - passes the id it was asked with instead, which is why this is a
 * constructor parameter rather than a field generated here.
 */
abstract class PlatformRequest(val eventId: UUID = UUID.randomUUID()) {
    protected val eventName = this::class.simpleName!!.replace("Request", "").convertToSnakeCase().uppercase()

    /**
     * This message as the JSON Pano reads.
     *
     * Open because a reply whose payload is decided at runtime - `FILE_RESULT`, whose fields
     * depend on which `FILE_*` request it answers - has no properties to map and builds its own
     * object instead. Every message with a fixed shape should leave this alone: deriving the
     * field names from the properties is what keeps the wire contract and the code in step.
     */
    open fun encode(): String {
        val response = mutableMapOf<String, Any?>(
            "event" to eventName
        )

        response.putAll(JsonObject.mapFrom(this).map)

        return JsonObject(response).encode()
    }
}
