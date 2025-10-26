package com.panomc.plugins.pano.core.platform

import com.panomc.plugins.pano.core.util.TextUtil.convertToSnakeCase
import io.vertx.core.json.JsonObject
import java.util.*

abstract class PlatformRequest {
    val eventId: UUID = UUID.randomUUID()

    protected val eventName = this::class.simpleName!!.replace("Request", "").convertToSnakeCase().uppercase()

    fun encode(): String {
        val response = mutableMapOf<String, Any?>(
            "event" to eventName
        )

        response.putAll(JsonObject.mapFrom(this).map)

        return JsonObject(response).encode()
    }
}
