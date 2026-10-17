package dev.dshpocket.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Thin helpers over kotlinx JsonElement so the transport code stays readable.
 *
 * Everything on this wire is JSON, and the Harness gateway schema is versioned
 * independently of this app, so the envelope is handled as a tree rather than
 * as generated DTOs. The accessors below are deliberately total: an unknown or
 * missing shape yields `null` instead of throwing, which keeps a future Host
 * field addition from crashing the phone.
 */
object Json {
    val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Text of a Harness content part list (`[{type:"text",text:"…"}]`). */
    fun contentText(parts: JsonElement?): String {
        val arr = parts.asArrOrNull() ?: return ""
        return arr.joinToString("\n") { part ->
            val o = part.asObjOrNull() ?: return@joinToString ""
            when (o.string("type")) {
                "text" -> o.string("text").orEmpty()
                else -> ""
            }
        }.trim()
    }
}

// ---------------------------------------------------------------- accessors

fun JsonElement?.asObjOrNull(): JsonObject? = this as? JsonObject

fun JsonElement?.asArrOrNull(): JsonArray? = this as? JsonArray

fun JsonElement?.asStringOrNull(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

fun JsonElement?.asBoolOrNull(): Boolean? = asStringOrNull()?.toBooleanStrictOrNull()

fun JsonElement?.asLongOrNull(): Long? = asStringOrNull()?.toLongOrNull()

fun JsonObject.string(key: String): String? = this[key].asStringOrNull()

fun JsonObject.bool(key: String): Boolean? = this[key].asBoolOrNull()

fun JsonObject.long(key: String): Long? = this[key].asLongOrNull()

fun JsonObject.obj(key: String): JsonObject? = this[key].asObjOrNull()

fun JsonObject.arr(key: String): JsonArray? = this[key].asArrOrNull()

// ----------------------------------------------------------------- builders

/** Build an object, skipping any pair whose value is `null`. */
fun jsonObjectOf(vararg pairs: Pair<String, JsonElement?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) if (v != null) put(k, v)
}

fun jsonString(value: String?): JsonElement? = value?.let(::JsonPrimitive)

fun jsonBool(value: Boolean?): JsonElement? = value?.let(::JsonPrimitive)

fun jsonLong(value: Long?): JsonElement? = value?.let(::JsonPrimitive)
