package dev.dshpocket.core

import dev.dshpocket.data.AppSettings
import dev.dshpocket.data.PairedDesktop
import dev.dshpocket.protocol.ApprovalRequest
import dev.dshpocket.protocol.ModelCatalog
import dev.dshpocket.protocol.ModelSelection
import dev.dshpocket.protocol.PermissionPreset

/**
 * Coarse connection health.
 *
 * Four states, not a boolean, because "not connected" has two very different
 * causes that need different UI: we are *trying* ([Connecting]) versus we *tried and
 * failed* ([Error], which carries a message). A boolean would force the error text
 * to be inferred from some other field.
 */
enum class Phase { Disconnected, Connecting, Connected, Error }

/**
 * What the UI knows about the link to the desktop.
 *
 * @param baseUrl the origin actually in use (e.g. `https://x.trycloudflare.com`);
 *   shown in the connection banner so a user can tell *which* desktop they reach
 *   when they have more than one.
 * @param deviceId the identifier the desktop's device list shows. Displayed in
 *   settings only — it is part of the credential, so it never goes to logs.
 * @param error a human-readable failure already shaped for display. `null` while
 *   healthy; this is the single field the banner reads.
 * @param hostHome the desktop's home directory, taken from the `$events` `ready`
 *   frame. Used for nothing but making "which machine am I talking to" concrete.
 */
data class ConnectionState(
    val phase: Phase = Phase.Disconnected,
    val baseUrl: String? = null,
    val deviceId: String? = null,
    val error: String? = null,
    val hostHome: String? = null,
) {
    val isConnected: Boolean get() = phase == Phase.Connected
}

/**
 * One row in the session list.
 *
 * Mirrors `session/list` output one-for-one. Nothing is invented here: if the
 * desktop did not report a title, [title] stays `null` and the UI renders a
 * placeholder, rather than us synthesising a name the desktop never used.
 *
 * @param running the Host's own view of whether a turn is in flight. It is the
 *   source for the running dot *and* for keeping the foreground service alive, so
 *   it is deliberately not optimistic.
 * @param agentAvailable false when the session's agent preset is missing on this
 *   desktop; the UI disables sending rather than failing at submit time.
 */
data class SessionSummary(
    val id: String,
    val title: String?,
    val running: Boolean,
    val updatedAt: Long,
    val cwd: String?,
    val agentAvailable: Boolean,
)

/**
 * A single rendered bubble in the conversation.
 *
 * @param key stable identity for the `LazyColumn`. Built from the record's own
 *   event identity so a re-delivered frame updates a row instead of appending a
 *   duplicate.
 * @param streaming true only for the synthetic in-progress bubble, which is not an
 *   item of [ChatState.items] but is rendered after them.
 * @param toolName set only for [Role.Tool]; surfaced as the "工具 · …" label so a
 *   long transcript still shows *which* tool ran.
 */
data class ChatItem(
    val key: String,
    val role: Role,
    val text: String,
    val streaming: Boolean = false,
    val isError: Boolean = false,
    val toolName: String? = null,
) {
    /**
     * [System] exists for transcript notices the Host itself inserted; it is not
     * used for local UI messages, which go through [ChatState.error] instead.
     */
    enum class Role { User, Assistant, Tool, System }
}

/**
 * Everything the chat screen needs.
 *
 * ### The invariant
 *
 * [status] is derived from the Host's own signals — `api-session/status` for
 * running, `turn/end.reason` for the outcome, `approval/request` for a pending
 * prompt — and **never** from an optimistic local guess. Concretely: tapping send
 * does not set `Running`; the app waits for the Host to say the turn started.
 *
 * This is what makes stopping verifiable. [stopState] only leaves
 * [StopState.None] once a cancel RPC has been *accepted*, and only reaches
 * [StopState.Confirmed] when a `turn/end` arrives afterwards. There is no code path
 * that sets `Confirmed` from a button press.
 *
 * @param streamingText the in-progress assistant text, kept separate from [items]
 *   so each delta does not rebuild the whole list.
 * @param approval the prompt currently awaiting an answer, or `null`.
 */
data class ChatState(
    val sessionId: String? = null,
    val title: String? = null,
    val status: Status = Status.Idle,
    val items: List<ChatItem> = emptyList(),
    val streamingText: String = "",
    val approval: ApprovalRequest? = null,
    val stopState: StopState = StopState.None,
    val error: String? = null,
    /**
     * True from the moment [openSession] starts following until the first
     * `snapshot` frame lands.
     *
     * Exists so "the desktop has not answered yet" is visually distinct from
     * "this conversation is genuinely empty". Without it, opening a session on a
     * slow or broken tunnel shows the same "把任务发给电脑" placeholder as a brand
     * new file, and the user concludes their history is gone — which is exactly
     * the report that made this field necessary.
     */
    val loadingHistory: Boolean = false,
    /**
     * The model and effort that the **computer** will use for the next turn.
     *
     * `null` means "not known yet", which is a distinct third state from "the
     * desktop's default" — painting a default here before the desktop answers
     * would tell the user they are on model X while the turn actually runs on Y.
     * Filled from `session/projections.modelSelection.next` on open, and from the
     * catalog's `default` for a brand-new session.
     */
    val model: ModelSelection? = null,
    /**
     * The permission preset the **computer** currently enforces, e.g.
     * `workspace-write`.
     *
     * Also `null` until known. This field is deliberately read from the desktop
     * rather than kept as local UI state: the phone showing "read-only" while the
     * computer is in `danger-full-access` is precisely the kind of mismatch that
     * gets someone's files overwritten.
     */
    val permission: String? = null,
    /** The catalog backing the model picker; `null` until it has been fetched. */
    val modelCatalog: ModelCatalog? = null,
    /** The presets backing the permission picker. Empty until fetched. */
    val permissionPresets: List<PermissionPreset> = emptyList(),
    /**
     * True while a model/permission change is in flight.
     *
     * Exists so the composer can show a spinner on the control the user just
     * touched instead of letting them fire five changes at a computer that answers
     * in hundreds of milliseconds over a tunnel.
     */
    val changingPreference: Boolean = false,
) {
    /**
     * [Done], [Failed] and [Cancelled] all mean "no turn is in flight" but differ in
     * *why*, and the difference is user-visible: the stop banner must not appear for
     * a task that finished on its own.
     *
     * `Done` deliberately covers two `turn/end` reasons: `completed` and
     * `max-tokens`. Running out of the model's token budget is a turn that *ended*,
     * not one that broke, and the honest thing to show is a finished turn rather
     * than an error banner the user cannot act on. See the `newStatus` mapping in
     * `PocketViewModel.handleEvent`.
     *
     * [Unknown] exists because a frozen reading is a lie. Status comes from exactly
     * one source — the `api-session/status` frames on the `$events` socket — so once
     * that socket is gone we receive nothing more and the last value stays in the
     * snapshot forever. Rendering it unchanged is what produced the screenshot where
     * the screen said 「运行中」 while a red banner underneath said the link had
     * failed: two contradictory claims, both from us. When the carrier is down the
     * chip says [Unknown] instead, which is the truth ("nobody has told us").
     */
    enum class Status { Idle, Queued, Running, AwaitingApproval, Done, Failed, Cancelled, Unknown }

    /**
     * The stop lifecycle. Four values because a pressed button is not a stopped
     * task.
     *
     * `None → Requested` on a successful cancel call. From there exactly two
     * transitions exist: `Requested → Confirmed` when a `turn/end` arrives, and
     * `Requested → Unconfirmed` when the 15 s watchdog fires first. `Unconfirmed` is
     * terminal for that attempt and tells the user the task may still be running —
     * which is the truth, and the whole reason this enum is not a boolean.
     */
    enum class StopState {
        /** No cancel in flight. */
        None,

        /** Cancel RPC accepted; the Host has not yet reported the turn over. */
        Requested,

        /** The Host reported `turn/end` after our request. */
        Confirmed,

        /** The RPC was accepted but no `turn/end` arrived in time. */
        Unconfirmed,
    }
}

/**
 * Pending approvals.
 *
 * A queue rather than a single slot: the desktop can raise a second request while
 * the first is unanswered, and dropping one would silently unblock nothing. [current]
 * is what the UI renders.
 */
data class ApprovalState(val pending: List<ApprovalRequest> = emptyList()) {
    val current: ApprovalRequest? get() = pending.firstOrNull()
}

/**
 * The immutable snapshot the whole UI renders from.
 *
 * One object instead of several independent flows, so a screen can never observe a
 * connection state from before a reconnect together with a session list from after
 * it. The ViewModel publishes whole snapshots; Compose diffs them.
 */
data class UiState(
    val connection: ConnectionState = ConnectionState(),
    val sessions: List<SessionSummary> = emptyList(),
    val sessionsLoading: Boolean = false,
    val chat: ChatState = ChatState(),
    val approvals: ApprovalState = ApprovalState(),
    val paired: PairedDesktop? = null,
    val settings: AppSettings = AppSettings(),
    /**
     * Which session the user has navigated into, or `null` when they are on the
     * workspace list.
     *
     * This is the **single source of truth for navigation**. It lives here rather
     * than in the composable because a `remember` inside a conditional branch is
     * discarded the moment that branch leaves the composition — which is exactly
     * how the old "it keeps jumping back into a session / cannot go back" bug was
     * born: the guard that was supposed to run only once got reset on every
     * return to the list.
     *
     * Kept deliberately separate from [ChatState.sessionId]: that one is "whose
     * history is loaded", this one is "what the user is looking at". They move
     * together in [dev.dshpocket.core.PocketViewModel.openSession] but are read
     * for different reasons.
     */
    val openSessionId: String? = null,
)