package com.panomc.plugins.pano.core

import com.panomc.plugins.pano.core.util.TextUtil.convertToSnakeCase
import io.vertx.core.json.JsonObject

abstract class ServerEventRequest {
    protected val eventName = this::class.simpleName!!.replace("Request", "").convertToSnakeCase().uppercase()

    fun encode(): String {
        val response = mutableMapOf<String, Any?>(
            "event" to eventName
        )

        response.putAll(JsonObject.mapFrom(this).map)

        return JsonObject(response).encode()

    }
}
