package dev.dshpocket.core

import dev.dshpocket.data.AppSettings
import dev.dshpocket.data.PairedDesktop
import dev.dshpocket.protocol.ApprovalRequest

/** Coarse connection health shown in the app bar and used for notifications. */
enum class Phase { Disconnected, Connecting, Connected, Error }

data class ConnectionState(
    val phase: Phase = Phase.Disconnected,
    val baseUrl: String? = null,
    val deviceId: String? = null,
    val error: String? = null,
    val hostHome: String? = null,
) {
    val isConnected: Boolean get() = phase == Phase.Connected
}

/** One row in the session list. */
data class SessionSummary(
    val id: String,
    val title: String?,
    val running: Boolean,
    val updatedAt: Long,
    val cwd: String?,
    val agentAvailable: Boolean,
)

/** A single rendered bubble in the conversation. */
data class ChatItem(
    val key: String,
    val role: Role,
    val text: String,
    val streaming: Boolean = false,
    val isError: Boolean = false,
    val toolName: String? = null,
) {
    enum class Role { User, Assistant, Tool, System }
}

/**
 * Everything the chat screen needs. [status] is derived from the Host's own
 * signals — `api-session/status` for running, `turn/end.reason` for the
 * outcome, `approval/asked` for the pending prompt — never from optimistic
 * local guesses. This is what makes "stop" verifiable: [stopState] only leaves
 * [StopState.None] once the Host has acknowledged.
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
) {
    enum class Status { Idle, Queued, Running, AwaitingApproval, Done, Failed, Cancelled }

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

data class ApprovalState(val pending: List<ApprovalRequest> = emptyList()) {
    val current: ApprovalRequest? get() = pending.firstOrNull()
}

/** Immutable snapshot the whole UI renders from. */
data class UiState(
    val connection: ConnectionState = ConnectionState(),
    val sessions: List<SessionSummary> = emptyList(),
    val sessionsLoading: Boolean = false,
    val chat: ChatState = ChatState(),
    val approvals: ApprovalState = ApprovalState(),
    val paired: PairedDesktop? = null,
    val settings: AppSettings = AppSettings(),
)
