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
 * Thin helpers over kotlinx `JsonElement`, so the transport code stays readable.
 *
 * ### Why a JSON tree and not generated DTOs
 *
 * The gateway's schema is versioned independently of this app. A phone that
 * pinned DTOs would break on the desktop's next release the moment a field was
 * added or renamed. Reading the envelope as a tree and using total accessors
 * means an unknown field is ignored rather than fatal — which is the correct
 * failure mode for a client that cannot be updated in lockstep with the desktop.
 *
 * ### Why the accessors are total
 *
 * Every accessor below returns `null` for a missing or wrongly-typed value
 * instead of throwing. That is a deliberate choice with a cost: a genuinely
 * malformed payload becomes `null` somewhere upstream rather than an exception
 * here. The compensating control is that callers must decide what `null` means
 * (see the `?: return` and `?: throw` sites in `SessionApi`), so a silent `null`
 * can never be mistaken for a successful value.
 *
 * ### A Kotlin scoping note for future contributors
 *
 * These are **top-level extension functions**, not members of [Json]. Member
 * extensions declared inside an `object` are only visible inside that object's
 * scope, so `value.asObjOrNull()` at a call site elsewhere in the package would
 * fail to resolve. Keeping them top-level is what makes them usable from
 * `SessionApi`, `GatewayClient` and the ViewModel alike.
 */
object Json {
    /**
     * Parser configuration.
     *
     * `ignoreUnknownKeys` and `isLenient` are both required in practice: the
     * desktop's payloads contain fields this app does not model, and some numeric
     * values arrive as strings.
     *
     * `explicitNulls = false` matters for outbound frames: it stops the encoder
     * from emitting `"field": null` for absent optional arguments. The gateway
     * validates argument objects strictly, so a spurious explicit null would be
     * rejected as an unexpected field.
     */
    val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * Text of a Harness content part list (`[{type:"text",text:"…"}]`).
     *
     * Parts are joined with a newline rather than concatenated, because a message
     * can contain several text blocks that were authored as separate paragraphs.
     *
     * Non-text parts (images, files, tool references) are skipped, not stringified.
     * This app renders text only; inventing a placeholder like `[image]` would
     * claim content it cannot actually show.
     */
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

/** The value as an object, or `null` if it is any other JSON kind. */
fun JsonElement?.asObjOrNull(): JsonObject? = this as? JsonObject

/** The value as an array, or `null` if it is any other JSON kind. */
fun JsonElement?.asArrOrNull(): JsonArray? = this as? JsonArray

/**
 * The value as a string.
 *
 * `JsonNull` is checked explicitly because `JsonNull` *is* a `JsonPrimitive` in
 * kotlinx.serialization, and its `.content` is the literal text `"null"` — which
 * would otherwise leak into the UI as a session titled "null".
 */
fun JsonElement?.asStringOrNull(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

/**
 * The value as a boolean.
 *
 * Goes through the string form because the desktop sometimes encodes booleans as
 * JSON strings. `toBooleanStrictOrNull` (not `toBoolean`) is used so an
 * unrecognised token yields `null` rather than a silently-false value.
 */
fun JsonElement?.asBoolOrNull(): Boolean? = asStringOrNull()?.toBooleanStrictOrNull()

/** The value as a long. Returns `null` for a non-numeric string. */
fun JsonElement?.asLongOrNull(): Long? = asStringOrNull()?.toLongOrNull()

/**
 * Typed field readers for responses.
 *
 * Each one returns `null` for a missing field *and* for a field of the wrong type —
 * deliberately the same answer, because both mean "this response does not tell us
 * what we asked for". The alternative (throwing) would turn one unexpected extra or
 * changed field into a hard failure of an otherwise usable reply.
 */
fun JsonObject.string(key: String): String? = this[key].asStringOrNull()

fun JsonObject.bool(key: String): Boolean? = this[key].asBoolOrNull()

fun JsonObject.long(key: String): Long? = this[key].asLongOrNull()

fun JsonObject.obj(key: String): JsonObject? = this[key].asObjOrNull()

fun JsonObject.arr(key: String): JsonArray? = this[key].asArrOrNull()

// ----------------------------------------------------------------- builders

/**
 * Build an object, skipping any pair whose value is `null`.
 *
 * This is what keeps outbound argument objects clean: optional fields simply do
 * not appear, which is what the gateway's strict argument validation expects.
 */
fun jsonObjectOf(vararg pairs: Pair<String, JsonElement?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) if (v != null) put(k, v)
}

/**
 * Outbound value builders.
 *
 * They all return a **nullable** element purely so they can be passed straight into
 * [jsonObjectOf], which drops nulls. That combination is what produces minimal
 * argument objects: absent optional arguments are omitted rather than sent as JSON
 * `null`, which the gateway's strict validator would reject.
 */
fun jsonString(value: String?): JsonElement? = value?.let(::JsonPrimitive)

fun jsonBool(value: Boolean?): JsonElement? = value?.let(::JsonPrimitive)

fun jsonLong(value: Long?): JsonElement? = value?.let(::JsonPrimitive)
