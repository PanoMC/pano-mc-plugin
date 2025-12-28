package com.panomc.plugins.pano.core.util.deseriliazer

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import io.vertx.core.json.JsonObject
import java.lang.reflect.Type

class JsonObjectDeserializer : JsonDeserializer<JsonObject> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): JsonObject {
        if (json.isJsonNull) {
            return JsonObject()
        }

        // Backwards compatible:
        // - previously: "context" might be sent as a string containing JSON
        // - now: "context" might be sent as a real JSON object
        return try {
            when {
                json.isJsonObject -> JsonObject(json.toString())
                json.isJsonPrimitive -> {
                    val raw = json.asString
                    if (raw.isBlank()) JsonObject() else JsonObject(raw)
                }
                else -> JsonObject()
            }
        } catch (_: Exception) {
            JsonObject()
        }
    }
}