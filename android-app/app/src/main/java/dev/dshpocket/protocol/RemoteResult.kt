package dev.dshpocket.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One `server-response` result: either a value or a [RemoteError].
 * Mirrors `RemoteResult<T>` from `@deepseek-ai/dsh-api-gateway`.
 */
sealed interface RemoteResult {
    data class Ok(val value: JsonElement?) : RemoteResult
    data class Err(val error: RemoteError) : RemoteResult
}

/**
 * The gateway's stable error envelope. `code` is namespaced by subsystem
 * (`gateway/...`, `session/...`) so callers can branch on it without parsing
 * human-readable text.
 */
data class RemoteError(
    val code: String,
    val message: String,
    val details: JsonObject? = null,
) {
    override fun toString(): String = "$code: $message"
}

/** Thrown by [GatewayClient] when the Host answered with `ok: false`. */
class RemoteCallException(val error: RemoteError) : Exception(error.toString())
