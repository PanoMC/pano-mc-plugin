package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest
import io.vertx.core.json.JsonObject
import java.util.UUID

/**
 * `FILE_RESULT` - the answer to any request Pano expects one to, correlated by [eventId].
 *
 * One name for every operation rather than one per request, exactly as the node daemon does it
 * (AGENT.md 2.4.4): Pano pairs a reply with its request by the id it asked with, so the name
 * carries nothing it does not already know, and a new request kind then needs nothing on this
 * side but a handler.
 *
 * [result] is spread across the top level of the message rather than nested under a key, because
 * that is the shape Pano reads - `{ eventId, ok, error?, ...payload }` - which is why this builds
 * its own JSON instead of letting [PlatformRequest] map the object's properties.
 */
class FileResultRequest(
    eventId: UUID,
    private val result: JsonObject
) : PlatformRequest(eventId) {
    override fun encode(): String = JsonObject()
        .put("event", eventName)
        .put("eventId", eventId.toString())
        .mergeIn(result)
        .encode()
}
