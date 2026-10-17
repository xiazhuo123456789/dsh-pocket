package dev.dshpocket.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One entry of a session journal, as delivered by `session/follow`.
 *
 * The journal is the desktop's own event log, so these are *observations of what
 * the desktop did*, not instructions from the phone. Observed `type` values
 * include `turn/start`, `turn/end`, `step/start`, `step/end`,
 * `assistant/message`, `user/message`, `tool/call`, `tool/result`,
 * `approval/asked`, `approval/decided`, `session/title`, `request/header`,
 * `request/context`, `agent/inbox/spliced` and `system/message`.
 *
 * @param type event name, verbatim from the desktop.
 * @param seq monotonic sequence number, used as a stable UI key so a re-delivered
 *   event updates a bubble instead of duplicating it.
 * @param time host-side timestamp in epoch milliseconds.
 * @param data the event payload; its shape depends entirely on [type].
 */
data class FollowEvent(
    val type: String,
    val seq: Long,
    val time: Long,
    val data: JsonElement?,
)

/**
 * How a prompt is delivered to a session that may already be running.
 *
 * This mirrors the desktop's own two modes exactly; there is no third option and
 * no client-side queue. Choosing [Steer] on an idle session is harmless — the
 * desktop simply starts a turn.
 */
enum class PromptMode(val wire: String) {
    /** Append to the inbox and run after the current turn. */
    Queue("queue"),

    /** Redirect the running turn. */
    Steer("steer"),
}

/**
 * A pending approval, decoded from a forwarded `$events` waterfall frame.
 *
 * ### The outcome vocabulary is deliberately small
 *
 * The desktop accepts exactly three outcomes on the wire: `{kind:"result",
 * value:"allowed-once"}`, `{kind:"rejected", error}` and `{kind:"next"}`. Two
 * further values (`cancelled`, `unavailable`) exist but are produced by the
 * desktop itself and cannot be asserted by a client.
 *
 * **There is no "allow always" and no stored grant.** Every escalated tool call
 * is a fresh decision. That is why the UI says "允许一次" rather than "始终允许",
 * and why this class offers no `allowAlways()` — inventing one would be a lie
 * about what the protocol can express.
 *
 * ### Why the phone should usually answer `next`
 *
 * The desktop may have several subscribers to the same waterfall. Returning
 * `next` defers to the others and to the desktop's own policy. This app only
 * returns an affirmative result when a human tapped the button, so a phone that
 * is merely connected never silently approves anything.
 *
 * Provenance: read from `@deepseek-ai/dsh-user-approval` (the `OUTCOMES`
 * constant) and `@deepseek-ai/dsh-api-gateway` (the waterfall frame shape), then
 * confirmed end-to-end. See `docs/sources.md`.
 */
data class ApprovalRequest(
    /** Correlates the reply; the desktop rejects an unknown id. */
    val eventId: String,
    /** Session (or subagent) the request belongs to. */
    val agentId: String?,
    /** Tool the desktop wants to run, e.g. `pwsh`. */
    val toolName: String,
    /** Tool-call id, when the desktop provides one. */
    val callId: String?,
    /** Human-readable justification, when the desktop provides one. */
    val reason: String?,
    /** The raw request object, kept for diagnostics and future fields. */
    val raw: JsonObject?,
) {
    /**
     * Human-readable summary for the approval card and notification.
     *
     * Built defensively: any of the parts may be missing, and the fallback text
     * exists so the card is never blank (a blank approval prompt would be worse
     * than useless — the user would be asked to authorise something unnamed).
     */
    val summary: String
        get() = buildString {
            if (toolName.isNotBlank()) append(toolName)
            if (!reason.isNullOrBlank()) {
                if (isNotEmpty()) append(" — ")
                append(reason)
            }
            if (isEmpty()) append("需要你的确认")
        }

    /** `allowed-once` — the only affirmative outcome the desktop accepts. */
    fun allowOnce(): JsonObject = buildJsonObject {
        put("kind", "result")
        put("value", "allowed-once")
    }

    /** Refuse. The tool call does not run. */
    fun reject(): JsonObject = buildJsonObject { put("kind", "rejected") }
}
