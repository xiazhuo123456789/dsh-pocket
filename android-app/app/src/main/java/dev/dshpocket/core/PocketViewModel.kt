package dev.dshpocket.core

import android.content.Context
import dev.dshpocket.data.CredentialStore
import dev.dshpocket.data.PairedDesktop
import dev.dshpocket.data.SettingsStore
import dev.dshpocket.notify.Notifier
import dev.dshpocket.protocol.ApprovalRequest
import dev.dshpocket.protocol.FollowEvent
import dev.dshpocket.protocol.GatewayClient
import dev.dshpocket.protocol.Json
import dev.dshpocket.protocol.PairingApi
import dev.dshpocket.protocol.PromptMode
import dev.dshpocket.protocol.RemoteCallException
import dev.dshpocket.protocol.SessionApi
import dev.dshpocket.protocol.arr
import dev.dshpocket.protocol.asBoolOrNull
import dev.dshpocket.protocol.asObjOrNull
import dev.dshpocket.protocol.asStringOrNull
import dev.dshpocket.protocol.bool
import dev.dshpocket.protocol.obj
import dev.dshpocket.protocol.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * Single source of truth for the whole app.
 *
 * Three rules make the design document's acceptance criteria testable, and all
 * three are enforced here rather than in the UI:
 *
 *  1. Every status shown originates from a Host signal, never from a local
 *     optimistic guess. `stop` in particular stays
 *     [ChatState.StopState.Requested] until the Host reports `turn/end`; only
 *     that event can promote it to [ChatState.StopState.Confirmed].
 *  2. A message counts as "sent" once the gateway answered `{accepted:true}` —
 *     not when the button was tapped.
 *  3. When the gateway is unreachable the UI shows an explicit error. It never
 *     pretends a request went through.
 */
class PocketViewModel(
    @Suppress("unused") private val appContext: Context,
    private val credentials: CredentialStore,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var client: GatewayClient? = null
    private var api: SessionApi? = null

    /** Pairing talks to the desktop's local-only `/api/pair` surface. */
    private val pairing = PairingApi()

    private var chatJob: Job? = null
    private var eventsJob: Job? = null
    private var followHandle: GatewayClient.StreamHandle? = null
    private var eventsHandle: GatewayClient.StreamHandle? = null

    /** Guards the transcript: a stale follow must never append to a new session. */
    private var activeSessionId: String? = null

    init {
        scope.launch {
            settings.settings.collect { s ->
                _state.update { it.copy(settings = s) }
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    /** Restore the stored credential and connect if one exists. */
    fun bootstrap() {
        val paired = credentials.load()
        _state.update { it.copy(paired = paired) }
        if (paired != null) connect(paired)
    }

    fun connect(paired: PairedDesktop) {
        disconnectInternal()
        val base = paired.baseUrl.trimEnd('/').toHttpUrlOrNull()
        if (base == null) {
            _state.update {
                it.copy(
                    connection = ConnectionState(
                        phase = Phase.Error,
                        error = "服务器地址无效：${paired.baseUrl}",
                    )
                )
            }
            return
        }
        val gw = GatewayClient(base, paired.deviceId, scope)
        client = gw
        api = SessionApi(gw)
        _state.update {
            it.copy(
                paired = paired,
                connection = ConnectionState(
                    phase = Phase.Connecting,
                    baseUrl = paired.baseUrl,
                    deviceId = paired.deviceId,
                ),
            )
        }
        scope.launch {
            try {
                gw.ensureMux()
                _state.update {
                    it.copy(
                        connection = it.connection.copy(
                            phase = Phase.Connected,
                            error = null,
                            hostHome = gw.hostHome,
                        )
                    )
                }
                notifier.cancelConnection()
                startEvents(gw)
                refreshSessions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onConnectionFailure(e)
            }
        }
    }

    private fun onConnectionFailure(e: Throwable) {
        val msg = describe(e)
        _state.update {
            it.copy(connection = it.connection.copy(phase = Phase.Error, error = msg))
        }
        scope.launch {
            if (_state.value.settings.notifyOnDisconnect) notifier.connectionLost(msg)
        }
    }

    private fun disconnectInternal() {
        chatJob?.cancel()
        eventsJob?.cancel()
        followHandle?.cancel()
        eventsHandle?.cancel()
        followHandle = null
        eventsHandle = null
        chatJob = null
        eventsJob = null
        client?.close()
        client = null
        api = null
        activeSessionId = null
    }

    fun unpair() {
        disconnectInternal()
        credentials.clear()
        _state.value = UiState()
        scope.launch { settings.setLastSession(null) }
    }

    /** Retry the stored credential without re-pairing. */
    fun reconnect() {
        credentials.load()?.let(::connect)
    }

    // --------------------------------------------------------------- pairing

    /**
     * Complete pairing from an invite link, then persist and connect.
     *
     * The `/api/pair` family is physically local to the desktop, so this is the
     * one moment the phone talks to the un-gated surface — `accept` is the act
     * of pairing itself. Everything afterwards goes through `/remote`.
     */
    fun pairWithInvite(rawLink: String) {
        val invite = pairing.parseInvite(rawLink)
        if (invite == null) {
            _state.update {
                it.copy(
                    connection = ConnectionState(
                        phase = Phase.Error,
                        error = "无法识别配对链接，请整段粘贴电脑上显示的链接",
                    )
                )
            }
            return
        }
        scope.launch {
            _state.update {
                it.copy(connection = ConnectionState(phase = Phase.Connecting, baseUrl = invite.baseUrl))
            }
            try {
                when (val result = pairing.accept(invite.baseUrl, invite.token)) {
                    is PairingApi.Result.Paired -> {
                        val paired = PairedDesktop(
                            baseUrl = invite.baseUrl,
                            deviceId = result.deviceId,
                            pairedAt = System.currentTimeMillis(),
                            label = invite.baseUrl,
                        )
                        credentials.save(paired)
                        _state.update { it.copy(paired = paired) }
                        connect(paired)
                    }
                    is PairingApi.Result.Failed -> _state.update {
                        it.copy(
                            connection = ConnectionState(
                                phase = Phase.Error,
                                baseUrl = invite.baseUrl,
                                error = "配对失败（${result.code}）：${result.message}",
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        connection = ConnectionState(
                            phase = Phase.Error,
                            baseUrl = invite.baseUrl,
                            error = describe(e),
                        )
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------- sessions

    fun refreshSessions() {
        val api = api ?: return
        _state.update { it.copy(sessionsLoading = true) }
        scope.launch {
            try {
                val sessions = api.list().map { s ->
                    SessionSummary(
                        id = s.sessionId,
                        title = s.title,
                        running = s.running,
                        updatedAt = s.updatedAt ?: 0L,
                        cwd = s.cwd,
                        agentAvailable = !s.blank,
                    )
                }
                _state.update { it.copy(sessions = sessions, sessionsLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(sessionsLoading = false) }
                onConnectionFailure(e)
            }
        }
    }

    fun newSession() {
        val api = api ?: return
        scope.launch {
            try {
                val sessionId = api.create()
                refreshSessions()
                openSession(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(chat = it.chat.copy(error = describe(e))) }
            }
        }
    }

    // ------------------------------------------------------------------ chat

    /** Subscribe to one session and replay its history. */
    fun openSession(sessionId: String) {
        val api = api ?: return
        chatJob?.cancel()
        followHandle?.cancel()
        followHandle = null
        activeSessionId = sessionId
        _state.update { it.copy(chat = ChatState(sessionId = sessionId)) }
        scope.launch { settings.setLastSession(sessionId) }

        chatJob = scope.launch {
            try {
                val handle = api.follow(sessionId)
                followHandle = handle
                handle.items.collect { value ->
                    if (activeSessionId != sessionId) return@collect
                    handleFollowFrame(value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (activeSessionId == sessionId) {
                    _state.update { it.copy(chat = it.chat.copy(error = describe(e))) }
                }
            }
        }
    }

    private fun handleFollowFrame(value: kotlinx.serialization.json.JsonElement?) {
        val api = api ?: return
        when (val frame = api.decodeFrame(value)) {
            is SessionApi.FollowFrame.Snapshot -> {
                val items = frame.records.mapNotNull { toChatItem(it) }
                _state.update { st ->
                    st.copy(
                        chat = st.chat.copy(
                            sessionId = frame.sessionId ?: st.chat.sessionId,
                            items = items,
                            status = if (items.isEmpty()) ChatState.Status.Idle else st.chat.status,
                        )
                    )
                }
            }
            is SessionApi.FollowFrame.Event -> handleEvent(frame.event)
            is SessionApi.FollowFrame.AssistantStream -> {
                val delta = api.assistantDelta(frame.frame)
                if (delta != null) {
                    _state.update { st ->
                        st.copy(
                            chat = st.chat.copy(
                                streamingText = st.chat.streamingText + delta.text,
                                status = ChatState.Status.Running,
                            )
                        )
                    }
                } else if (frame.frame.string("type") == "end") {
                    commitStreaming()
                }
            }
            is SessionApi.FollowFrame.Raw -> Unit
        }
    }

    private fun handleEvent(event: FollowEvent) {
        when (event.type) {
            "turn/start" -> _state.update {
                it.copy(chat = it.chat.copy(status = ChatState.Status.Running))
            }

            "turn/end" -> {
                val data = event.data.asObjOrNull()
                val reason = data?.obj("reason")?.string("kind")
                val wasStopping = _state.value.chat.stopState
                commitStreaming()
                _state.update { st ->
                    st.copy(
                        chat = st.chat.copy(
                            status = when (reason) {
                                "completed" -> ChatState.Status.Done
                                "cancelled", "aborted" -> ChatState.Status.Cancelled
                                "failed", "error" -> ChatState.Status.Failed
                                else -> ChatState.Status.Idle
                            },
                            // The Host reporting the turn over is the only proof
                            // that a cancel request actually took effect.
                            stopState = when (wasStopping) {
                                ChatState.StopState.Requested,
                                ChatState.StopState.Unconfirmed,
                                -> ChatState.StopState.Confirmed
                                else -> wasStopping
                            },
                        )
                    )
                }
                scope.launch {
                    val s = _state.value.settings
                    if (s.notifyOnDone && reason != "cancelled") {
                        notifier.taskFinished(
                            sessionId = _state.value.chat.sessionId.orEmpty(),
                            title = _state.value.chat.title ?: "会话",
                            success = reason == "completed",
                        )
                    }
                }
                refreshSessions()
            }

            "assistant/message" -> {
                val data = event.data.asObjOrNull()
                val message = data?.obj("message") ?: data
                val text = Json.contentText(message?.get("content"))
                if (text.isNotBlank()) {
                    _state.update { st ->
                        val last = st.chat.items.lastOrNull()
                        // The streamed text was already committed; avoid a
                        // duplicate bubble when block-end and assistant/message
                        // carry the same body.
                        if (last != null && last.role == ChatItem.Role.Assistant && last.text == text) {
                            st.copy(chat = st.chat.copy(streamingText = ""))
                        } else {
                            st.copy(
                                chat = st.chat.copy(
                                    items = st.chat.items + ChatItem(
                                        key = "a-${event.seq}",
                                        role = ChatItem.Role.Assistant,
                                        text = text,
                                    ),
                                    streamingText = "",
                                )
                            )
                        }
                    }
                }
            }

            "user/message" -> {
                val data = event.data.asObjOrNull() ?: return
                // Only echo genuine user turns, not injected runtime context.
                if (data.obj("source")?.string("kind") != "user") return
                val text = Json.contentText(data["content"])
                if (text.isBlank()) return
                _state.update { st ->
                    if (st.chat.items.any { it.key == "u-${event.seq}" }) st
                    else st.copy(
                        chat = st.chat.copy(
                            items = st.chat.items + ChatItem(
                                key = "u-${event.seq}",
                                role = ChatItem.Role.User,
                                text = text,
                            )
                        )
                    )
                }
            }

            "tool/call" -> {
                val name = event.data.asObjOrNull()?.string("name") ?: return
                _state.update { st ->
                    st.copy(
                        chat = st.chat.copy(
                            items = st.chat.items + ChatItem(
                                key = "t-${event.seq}",
                                role = ChatItem.Role.Tool,
                                text = name,
                                toolName = name,
                            )
                        )
                    )
                }
            }

            "tool/result" -> {
                val msg = event.data.asObjOrNull()?.obj("message")
                val text = Json.contentText(msg?.arr("content"))
                if (text.isBlank()) return
                _state.update { st ->
                    st.copy(
                        chat = st.chat.copy(
                            items = st.chat.items + ChatItem(
                                key = "tr-${event.seq}",
                                role = ChatItem.Role.Tool,
                                text = text.take(2000),
                                isError = msg?.bool("isError") == true,
                            )
                        )
                    )
                }
            }

            "approval/asked" -> _state.update { st ->
                st.copy(chat = st.chat.copy(status = ChatState.Status.AwaitingApproval))
            }

            "approval/decided" -> _state.update { st ->
                st.copy(
                    chat = st.chat.copy(
                        status = ChatState.Status.Running,
                        approval = null,
                    ),
                    approvals = ApprovalState(st.approvals.pending.drop(1)),
                )
            }

            "session/title" -> {
                val title = event.data.asObjOrNull()?.string("title") ?: return
                _state.update { it.copy(chat = it.chat.copy(title = title)) }
            }
        }
    }

    private fun commitStreaming() {
        _state.update { st ->
            val buf = st.chat.streamingText
            if (buf.isBlank()) st
            else st.copy(
                chat = st.chat.copy(
                    items = st.chat.items + ChatItem(
                        key = "s-${System.nanoTime()}",
                        role = ChatItem.Role.Assistant,
                        text = buf,
                    ),
                    streamingText = "",
                )
            )
        }
    }

    // ----------------------------------------------------------------- send

    fun send(text: String, mode: PromptMode = PromptMode.Queue) {
        val api = api ?: return
        val sessionId = _state.value.chat.sessionId ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        // Show the bubble immediately, but keep the status honest: the send is
        // only acknowledged once the gateway answers `{accepted:true}`.
        _state.update { st ->
            st.copy(
                chat = st.chat.copy(
                    items = st.chat.items + ChatItem(
                        key = "local-${UUID.randomUUID()}",
                        role = ChatItem.Role.User,
                        text = body,
                    ),
                    status = ChatState.Status.Queued,
                    error = null,
                )
            )
        }
        scope.launch {
            try {
                val accepted = api.prompt(sessionId, body, mode)
                if (!accepted) {
                    _state.update { st ->
                        st.copy(
                            chat = st.chat.copy(
                                status = ChatState.Status.Idle,
                                error = "电脑未接受这条任务",
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { st ->
                    st.copy(
                        chat = st.chat.copy(
                            status = ChatState.Status.Idle,
                            error = "发送失败：${describe(e)}",
                        )
                    )
                }
            }
        }
    }

    /**
     * Ask the desktop to stop the running turn.
     *
     * The UI must not claim success here: it transitions to
     * [ChatState.StopState.Requested] and only [handleEvent]'s `turn/end` handler
     * can promote it to [ChatState.StopState.Confirmed]. A watchdog downgrades
     * to [ChatState.StopState.Unconfirmed] if the Host never answers, so the user
     * is told the truth instead of seeing a button that "looked like it worked".
     */
    fun stop() {
        val api = api ?: return
        val sessionId = _state.value.chat.sessionId ?: return
        _state.update { it.copy(chat = it.chat.copy(stopState = ChatState.StopState.Requested)) }
        scope.launch {
            try {
                val accepted = api.cancel(sessionId)
                if (!accepted) {
                    _state.update { st ->
                        st.copy(chat = st.chat.copy(stopState = ChatState.StopState.Unconfirmed))
                    }
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { st ->
                    st.copy(
                        chat = st.chat.copy(
                            stopState = ChatState.StopState.Unconfirmed,
                            error = "停止请求失败：${describe(e)}",
                        )
                    )
                }
                return@launch
            }
            val confirmed = withTimeoutOrNull(15_000) {
                while (_state.value.chat.stopState == ChatState.StopState.Requested) delay(120)
                _state.value.chat.stopState == ChatState.StopState.Confirmed
            }
            if (confirmed != true) {
                _state.update { st ->
                    if (st.chat.stopState == ChatState.StopState.Requested) {
                        st.copy(chat = st.chat.copy(stopState = ChatState.StopState.Unconfirmed))
                    } else st
                }
            }
        }
    }

    fun dismissChatError() {
        _state.update { it.copy(chat = it.chat.copy(error = null)) }
    }

    // -------------------------------------------------------------- approval

    private fun startEvents(gw: GatewayClient) {
        eventsJob?.cancel()
        eventsJob = scope.launch {
            try {
                val handle = gw.openEvents()
                eventsHandle = handle
                handle.items.collect { value ->
                    val api = api
                    // Approvals arrive on $events, not on session/follow.
                    val approval = api?.decodeApproval(value)
                    if (approval != null) {
                        onApprovalRequested(approval)
                        return@collect
                    }
                    val o = value.asObjOrNull() ?: return@collect
                    if (o.string("type") == "emit") {
                        handleEmit(o.string("event"), o.arr("args"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (_state.value.connection.phase == Phase.Connected) onConnectionFailure(e)
            }
        }
    }

    private fun handleEmit(event: String?, args: JsonArray?) {
        if (event == null || args == null) return
        val sessionId = args.firstOrNull().asStringOrNull() ?: return
        when (event) {
            "api-session/status" -> {
                val running = args.getOrNull(1).asBoolOrNull() ?: return
                _state.update { st ->
                    val sessions = st.sessions.map {
                        if (it.id == sessionId) it.copy(running = running) else it
                    }
                    val chat = if (st.chat.sessionId == sessionId && running &&
                        st.chat.status == ChatState.Status.Idle
                    ) {
                        st.chat.copy(status = ChatState.Status.Running)
                    } else {
                        st.chat
                    }
                    st.copy(sessions = sessions, chat = chat)
                }
            }
            "api-session/added" -> refreshSessions()
        }
    }

    private fun onApprovalRequested(approval: ApprovalRequest) {
        _state.update { st ->
            st.copy(
                approvals = ApprovalState(st.approvals.pending + approval),
                chat = st.chat.copy(
                    status = ChatState.Status.AwaitingApproval,
                    approval = st.chat.approval ?: approval,
                ),
            )
        }
        scope.launch {
            if (_state.value.settings.notifyOnApproval) {
                notifier.approvalNeeded(
                    sessionId = _state.value.chat.sessionId.orEmpty(),
                    eventId = approval.eventId,
                    description = approval.summary,
                )
            }
        }
    }

    /** Answer the pending approval. `allow` maps to the only grant the Host accepts. */
    fun answerApproval(approval: ApprovalRequest, allow: Boolean) {
        val gw = client ?: return
        val outcome: JsonObject = if (allow) approval.allowOnce() else approval.reject()
        _state.update { st ->
            st.copy(
                approvals = ApprovalState(
                    st.approvals.pending.filterNot { it.eventId == approval.eventId }
                ),
                chat = st.chat.copy(
                    approval = st.chat.approval?.takeIf { it.eventId != approval.eventId },
                    status = ChatState.Status.Running,
                ),
            )
        }
        scope.launch {
            try {
                gw.answerEvent(approval.eventId, outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { st ->
                    st.copy(chat = st.chat.copy(error = "提交决定失败：${describe(e)}"))
                }
            }
        }
    }

    fun answerCurrentApproval(allow: Boolean) {
        val pending = _state.value.approvals.current
            ?: _state.value.chat.approval
            ?: return
        answerApproval(pending, allow)
    }

    /** Answer an approval by event id, used by the notification action receiver. */
    fun answerApprovalByEventId(eventId: String, allow: Boolean): Boolean {
        val pending = _state.value.approvals.pending.firstOrNull { it.eventId == eventId }
            ?: return false
        answerApproval(pending, allow)
        return true
    }

    /**
     * Leave the chat screen without forgetting the session.
     *
     * The follow stream is torn down so a backgrounded phone is not holding a
     * socket per opened session, but the last session id stays in settings so
     * reopening the app returns to it.
     */
    fun closeSession() {
        chatJob?.cancel()
        chatJob = null
        followHandle?.cancel()
        followHandle = null
        activeSessionId = null
        _state.update { it.copy(chat = ChatState()) }
    }

    // ----------------------------------------------------------------- misc

    fun setNotifyOnDone(v: Boolean) = scope.launch { settings.setNotifyOnDone(v) }
    fun setNotifyOnApproval(v: Boolean) = scope.launch { settings.setNotifyOnApproval(v) }
    fun setNotifyOnDisconnect(v: Boolean) = scope.launch { settings.setNotifyOnDisconnect(v) }
    fun setKeepScreenAwake(v: Boolean) = scope.launch { settings.setKeepScreenAwake(v) }

    private fun describe(e: Throwable): String = when (e) {
        is RemoteCallException -> "${e.error.code}: ${e.error.message}"
        else -> e.message ?: e::class.java.simpleName
    }

    fun currentGateway(): GatewayClient? = client

    /** Resume the most recent session, if the user had one and it still exists. */
    fun resumeLastSession(lastSessionId: String?) {
        if (lastSessionId.isNullOrBlank()) return
        if (_state.value.sessions.any { it.id == lastSessionId }) openSession(lastSessionId)
    }
}
