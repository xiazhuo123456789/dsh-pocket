package dev.dshpocket.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One entry of a session journal, as delivered by `session/follow`. */
data class FollowEvent(
    val type: String,
    val seq: Long,
    val time: Long,
    val data: JsonElement?,
)

/** How a prompt is delivered to a session that may already be running. */
enum class PromptMode(val wire: String) {
    /** Append to the inbox and run after the current turn. */
    Queue("queue"),

    /** Redirect the running turn. */
    Steer("steer"),
}

/**
 * A pending approval, decoded from a forwarded `$events` waterfall frame.
 *
 * The Host's outcome vocabulary is exactly `allowed-once` / `rejected` / `next`.
 * There is no "allow always" and no stored grant: every escalated tool call is a
 * fresh decision, which is why the UI says "允许一次" rather than "始终允许".
 */
data class ApprovalRequest(
    val eventId: String,
    val agentId: String?,
    val toolName: String,
    val callId: String?,
    val reason: String?,
    val raw: JsonObject?,
) {
    /** Human-readable summary for the approval card and notification. */
    val summary: String
        get() = buildString {
            if (toolName.isNotBlank()) append(toolName)
            if (!reason.isNullOrBlank()) {
                if (isNotEmpty()) append(" — ")
                append(reason)
            }
            if (isEmpty()) append("需要你的确认")
        }

    /** `allowed-once` — the only affirmative outcome the Host accepts. */
    fun allowOnce(): JsonObject = buildJsonObject {
        put("kind", "result")
        put("value", "allowed-once")
    }

    fun reject(): JsonObject = buildJsonObject { put("kind", "rejected") }
}
