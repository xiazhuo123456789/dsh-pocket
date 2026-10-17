package dev.dshpocket.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One unary RPC result, decoded from the `result` field of a `server-response`.
 *
 * A response is either a value (`{"ok":true,"value":…}`) or an error
 * (`{"ok":false,"error":{…}}`). Modelling it as a sealed type instead of a nullable
 * value makes the two cases exhaustively distinguishable at every call site, so no
 * path can silently treat a failed call as `null`.
 *
 * ### Attribution
 *
 * Mirrors `RemoteResult<T>` from `@deepseek-ai/dsh-api-gateway`. Observed from the
 * live desktop and cross-checked in `tests/conformance.mjs`; recorded in
 * `docs/sources.md`, §"Primary sources: the shipped software".
 */
sealed interface RemoteResult {
    data class Ok(val value: JsonElement?) : RemoteResult
    data class Err(val error: RemoteError) : RemoteResult
}

/**
 * The gateway's stable error envelope.
 *
 * `code` is namespaced by subsystem (`gateway/…`, `session/…`) so callers can branch
 * on it — e.g. `gateway/arguments-invalid`, `session/not-found` — without parsing
 * human-readable text. `details` is an optional structured payload the gateway may
 * attach; it is kept opaque here because its shape varies by error.
 *
 * `toString` collapses to `code: message` so logging and error bubbles carry the
 * machine-readable code and the human text together without extra wiring.
 */
data class RemoteError(
    val code: String,
    val message: String,
    val details: JsonObject? = null,
) {
    override fun toString(): String = "$code: $message"
}

/**
 * Thrown by [GatewayClient] when the Host answered with `ok: false`.
 *
 * Distinct from a transport failure (socket down → `IOException`) so callers can
 * tell "the desktop responded and refused" apart from "we never got an answer".
 * The two need very different user messaging.
 */
class RemoteCallException(val error: RemoteError) : Exception(error.toString())