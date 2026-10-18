package dev.dshpocket.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One selectable model, as the desktop reports it.
 *
 * ### Why this is a flat choice and not a (provider, model) pair
 *
 * On the wire the pair *is* two fields, but on screen it is one decision: the user
 * thinks "use the Pro model", not "use provider X then model Y". [provider] is kept
 * only because `session/selectModel` requires it back verbatim, and because two
 * providers can expose a model with the same id.
 *
 * @param provider the provider id to send back, e.g. `deepseek-official`.
 * @param model the model id within that provider, e.g. `deepseek-v4-pro`.
 * @param displayName the label the desktop prefers; falls back to [model] rather
 *   than showing a raw id when the desktop omitted a name.
 * @param description one-line blurb, when the desktop supplies one. Shown as a
 *   second line in the picker — it is the only thing that distinguishes two models
 *   with similar names.
 * @param efforts the reasoning-effort steps this model advertises, in the
 *   desktop's own order. Empty means "this model does not offer the knob", which
 *   is why the UI hides the effort row instead of showing a disabled one.
 * @param defaultEffort the effort the desktop would pick if the user says nothing.
 */
data class ModelOption(
    val provider: String,
    val model: String,
    val displayName: String,
    val description: String?,
    val efforts: List<ReasoningEffort>,
    val defaultEffort: String?,
) {
    /**
     * Stable identity for `LazyColumn` keys and for "is this the current one".
     *
     * Composed of both ids because provider and model id are only unique together
     * — two providers really do ship a model called `deepseek-flash`.
     */
    val key: String get() = "$provider/$model"
}

/**
 * One reasoning-effort step (`off` / `low` / `high` / `max` on current builds).
 *
 * The set is **not** hard-coded in this app: it comes from the catalog, because a
 * provider may support none, all, or a different vocabulary. Passing an effort a
 * model does not support makes the desktop reject the whole prompt with
 * `UNSUPPORTED_REASONING_EFFORT`, so guessing here is not free.
 */
data class ReasoningEffort(
    val id: String,
    val name: String,
    val description: String?,
)

/**
 * One group of models sharing a provider, as `session/modelCatalog` reports it.
 *
 * Grouped rather than flattened because the desktop's own picker groups them, and
 * a flat list of 20+ models from 9 providers is unnavigable on a phone.
 */
data class ModelGroup(
    val id: String,
    val name: String,
    val models: List<ModelOption>,
)

/**
 * What the composer needs to draw the model picker: the groups plus the desktop's
 * own default.
 *
 * @param defaultSelection what a brand-new session uses. Shown as "默认" so the user
 *   can tell "I chose this" from "the desktop chose this".
 * @param failures providers the desktop could not enumerate. Surfaced rather than
 *   hidden: an empty group here means "provider misconfigured on the computer",
 *   which is actionable, not "no models".
 */
data class ModelCatalog(
    val groups: List<ModelGroup>,
    val defaultSelection: ModelSelection,
    val failures: List<String>,
)

/**
 * A (provider, model, effort) triple — both what the user picked and what the
 * desktop reports back as currently selected.
 *
 * @param reasoningEffort nullable because a model without the knob has no effort at
 *   all; sending `null` must omit the field rather than send JSON `null`, which the
 *   gateway's strict argument check would reject.
 */
data class ModelSelection(
    val provider: String,
    val model: String,
    val reasoningEffort: String?,
)

/**
 * One permission preset, e.g. `read-only` / `workspace-write` / `danger-full-access`.
 *
 * These are the desktop's own presets, fetched from `permissionPresets/catalog`.
 * The app deliberately does not translate or invent them: a mismatch between what
 * the phone claims a preset means and what the computer enforces is a security
 * bug, so the honest move is to show the desktop's own vocabulary.
 *
 * @param dangerous true for presets that let the agent write outside the workspace
 *   or run unrestricted commands. Used only to colour the warning — the list itself
 *   still comes from the desktop, so a preset added upstream appears without a code
 *   change.
 */
data class PermissionPreset(
    val value: String,
    val name: String,
    val dangerous: Boolean,
)

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
