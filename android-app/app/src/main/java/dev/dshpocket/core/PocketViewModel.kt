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
 * ### Three rules, all enforced here rather than in the UI
 *
 * The design document's acceptance criteria reduce to three claims that only hold
 * if they are enforced in one place, so they live in this class and the UI is
 * allowed to render nothing but what this class publishes:
 *
 *  1. **Every status originates from a Host signal**, never a local optimistic
 *     guess. `stop` in particular stays [ChatState.StopState.Requested] until the
 *     Host reports `turn/end`; only that event can promote it to
 *     [ChatState.StopState.Confirmed]. No code path sets `Confirmed` from a tap.
 *  2. **A message counts as "sent" once the gateway answered `{accepted:true}`** —
 *     not when the button was tapped. The local bubble is rendered for
 *     responsiveness, but the status only moves to `Queued` and can fall back to
 *     `Idle` with an error if the desktop refuses.
 *  3. **When the gateway is unreachable the UI shows an explicit error.** It never
 *     pretends a request went through.
 *
 * ### Ownership
 *
 * This object owns every long-lived resource in the app: the [GatewayClient] (one
 * mux socket), the followed-session stream, the `$events` stream, and the pending
 * approval queue. It is constructed once by
 * [dev.dshpocket.PocketApp] so that a notification action and the chat screen
 * answer the *same* approval (see [answerApprovalByEventId]).
 *
 * ### Concurrency model
 *
 * All public methods are main-thread-safe in the sense that matters: they only
 * touch `_state` through [MutableStateFlow.update], which is atomic, so a frame
 * arriving on a coroutine thread and a tap arriving on the main thread cannot
 * interleave into a torn state. Coroutines use the injected [scope] (Default in
 * production) and suspend on network calls without blocking the main thread.
 *
 * @param appContext unused beyond construction; retained so the signature is
 *   stable if a future component needs resources. Marked `unused` deliberately.
 * @param credentials encrypted store for the pairing credential.
 * @param settings DataStore-backed preferences.
 * @param notifier the notification facade; never called on the main thread.
 * @param scope scope for all network work; `SupervisorJob` so one failed
 *   reconnect does not cancel the `$events` collector that approvals depend on.
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

    /**
     * Jobs are kept so a reconnect can cancel the previous generation before
     * starting a new one. Without this, two `$events` collectors could coexist and
     * both try to answer the same approval.
     */
    private var chatJob: Job? = null
    private var eventsJob: Job? = null
    private var followHandle: GatewayClient.StreamHandle? = null
    private var eventsHandle: GatewayClient.StreamHandle? = null

    /**
     * Guards the transcript: a stale follow must never append to a new session.
     *
     * Cancelling a coroutine does not instantly stop an OkHttp reader thread that
     * has already queued a frame, so every collected frame re-checks this field
     * before touching state.
     */
    private var activeSessionId: String? = null

    /**
     * Texts this client has already painted as optimistic user bubbles and is
     * waiting for the desktop to echo back.
     *
     * FIFO, because the desktop emits `user/message` echoes in the order it
     * accepted the prompts. `send` appends, the `user/message` handler pops the
     * head on an exact match. See [send] for why a hash-keyed lookup was the
     * wrong approach.
     *
     * Cleared by [openSession]/[closeSession]: switching conversations means the
     * backlog no longer describes anything on screen.
     */
    private val pendingUserTexts = ArrayDeque<String>()

    /** Monotonic counter for local bubble keys — never reused, never a hash. */
    private var nextLocalSeq: Long = 0L

    /**
     * 退避重连的调度任务与轮次。
     *
     * ### 为什么需要它（上一版最严重的缺陷）
     *
     * 之前承载一断就再没人管：`GatewayClient` 把 `muxReady` 置 false、把错误塞进
     * 各流，然后就结束了。手机上表现为一行永远不会消失的
     * `gateway/mux-failed: Remote mux failed (HTTP 0)`，只有用户自己摸到设置页点
     * 「重新连接」才能恢复 —— 而界面上一片正常，没人会想到去点那里。
     *
     * 隧道抖动、切后台、桌面端重启都会触发。所以这里做**自动**恢复：断开后按
     * 1s/2s/4s/8s/16s/30s 退避重试，封顶 30s，直到成功或被主动断开
     * （[disconnectInternal] 会取消这个任务，避免用户登出后还在偷偷重连）。
     *
     * @param reconnectJob 当前等待中的重连；`null` 表示没有在重试。
     * @param reconnectAttempt 已连续失败的次数，用来算下一次的等待时长；成功后归零。
     */
    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0

    init {
        // Mirror persisted settings into the snapshot so the UI has exactly one
        // object to read rather than combining flows itself.
        scope.launch {
            settings.settings.collect { s ->
                _state.update { it.copy(settings = s) }
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Restore the stored credential and connect if one exists.
     *
     * Called from `MainActivity` on first composition. A failure here leaves
     * [UiState.paired] set but the connection in [Phase.Error], so the UI can show
     * the error while still offering settings — the user does not have to re-pair
     * just because the desktop was asleep.
     */
    fun bootstrap() {
        val paired = credentials.load()
        _state.update { it.copy(paired = paired) }
        if (paired != null) connect(paired)
    }

    /**
     * Open a connection generation for [paired].
     *
     * "Generation" is the right word: the previous client, its sockets and its jobs
     * are all torn down first ([disconnectInternal]), because two generations must
     * never share a `$events` subscription — the `clientId` is per-connection and
     * approvals would become unanswerable.
     *
     * The base URL is parsed and rejected *before* the state moves to `Connecting`,
     * so a malformed stored credential produces an immediate, specific error rather
     * than a connection attempt that hangs.
     */
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
        // 承载断开时由 GatewayClient 主动上报，这里负责退避重连。注册在建立连接
        // 之前，避免"socket 刚开就断"的那一瞬间漏掉通知。
        gw.onCarrierLost = { failure -> onCarrierLost(failure) }
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
                // The mux socket is opened here, once, and reused by every stream.
                gw.ensureMux()
                // 成功了才把重试计数清零：半路失败不能重置退避，否则会变成
                // 每 1 秒重连一次的忙等。
                reconnectAttempt = 0
                _state.update {
                    it.copy(
                        connection = it.connection.copy(
                            phase = Phase.Connected,
                            error = null,
                            hostHome = gw.hostHome,
                        )
                    )
                }
                // A successful connect supersedes any "connection lost" card.
                notifier.cancelConnection()
                startEvents(gw)
                refreshSessions(showSpinner = false)
                // 重连成功后把用户正看着的那个会话重新订阅一遍。
                //
                // 不重订阅的话界面会停在旧内容上：转写不会再更新（follow 流已经
                // 跟着上一代 socket 一起死了），状态也停在 Unknown。
                //
                // `keepContent = true` 是这一版新加的：早先重订阅会先把转写清成
                // "正在读取记录…"，于是每次自动重连用户都看到屏幕闪白一下再
                // 重新长出来 —— 也就是反馈里说的"莫名其妙有一点小 bug 闪现"。
                // 内容本来就还在，重订阅只是为了接上增量，没有理由先把它抹掉。
                //
                // 放在 refreshSessions 之后：这样列表已经刷新过，即使重订阅失败，
                // 用户退回去看到的也是最新的会话列表。
                _state.value.openSessionId?.let { openSession(it, keepContent = true) }
            } catch (e: CancellationException) {
                // Never swallow cancellation: it means the caller replaced us.
                throw e
            } catch (e: Throwable) {
                onConnectionFailure(e)
                scheduleReconnect()
            }
        }
    }

    /**
     * 承载（mux socket）断开时的处理：降级状态、通知用户、排一次重连。
     *
     * ### 为什么这里必须把状态改成"未知"而不是留着
     *
     * `api-session/status` 是运行状态的唯一来源。链路一断就再也收不到它，最后
     * 一次读数会被永久冻结在快照里 —— 界面上「● 运行中」和下面「连接失败」同时
     * 显示，两句都是我们自己说的，互相矛盾。改成 [ChatState.Status.Unknown]
     * 才是诚实的：没人告诉我们当前状态。
     *
     * 会话列表和转写**不清空**：掉线不等于会话被删。显示旧数据 + 明确横幅，
     * 好过清空成空白（那是更严重的误判）。
     */
    private fun onCarrierLost(failure: dev.dshpocket.protocol.RemoteError) {
        _state.update {
            it.copy(
                connection = it.connection.copy(phase = Phase.Error, error = failure.message),
                // 只把"正在跑/排队中"降级为未知；已经结束的（Done/Failed/Cancelled）
                // 是历史事实，不会因为掉线就变成假的。
                chat = it.chat.copy(
                    status = if (it.chat.status == ChatState.Status.Running ||
                        it.chat.status == ChatState.Status.Queued ||
                        it.chat.status == ChatState.Status.AwaitingApproval
                    ) ChatState.Status.Unknown else it.chat.status,
                    // 等待审批的卡片也必须收掉：那条审批已经答不了了（clientId 随
                    // socket 失效），留着按钮只会让用户白点。
                    approval = null,
                    // 转写里那个"正在生成"的灰字气泡同理，它再也不会变长了。
                    streamingText = "",
                ),
            )
        }
        scope.launch {
            if (_state.value.settings.notifyOnDisconnect) notifier.connectionLost(failure.message)
        }
        scheduleReconnect()
    }

    /**
     * 排一次退避重连。
     *
     * 1s → 2s → 4s → 8s → 16s → 30s 封顶。用指数退避而不是固定 1 秒，是因为
     * 桌面端可能整晚关机，固定间隔会让手机整夜每秒钟醒一次 —— 费电，而且在
     * 系统眼里这就是一个后台滥用网络的 app。
     *
     * 幂等：已有等待中的重连就直接返回，不会叠出两条重连链。
     */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val paired = credentials.load() ?: return
        val delayMs = (1_000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            // `connect` 会先 `disconnectInternal`，把这一代 socket 与 job 全部
            // 收干净，再开新的一代，因此不会出现两代共用 `$events` 的情况。
            connect(paired)
        }
    }


    /**
     * Record a connection failure in the snapshot and (optionally) notify.
     *
     * Note that the session list and transcript are **not** cleared. A transient
     * disconnect is not evidence that the user's sessions were deleted, and wiping
     * them would be a worse lie than showing stale rows behind an error banner.
     */
    private fun onConnectionFailure(e: Throwable) {
        val msg = describe(e)
        _state.update {
            it.copy(connection = it.connection.copy(phase = Phase.Error, error = msg))
        }
        scope.launch {
            if (_state.value.settings.notifyOnDisconnect) notifier.connectionLost(msg)
        }
    }

    /**
     * Tear down the current connection generation.
     *
     * Order matters: jobs first (so no new work starts), then the stream handles
     * (so no frames are delivered), then the client itself, then the identity
     * fields. Doing it in this order means a frame cannot arrive between "client is
     * closed" and "we cleared the session id" and resurrect stale state.
     */
    private fun disconnectInternal() {
        // 先取消重连链：否则用户主动「断开 / 取消配对」之后，这条链还会在几秒后
        // 把连接重新建起来，表现为"我明明断了它自己又连上了"。
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
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

    /**
     * Forget the local credential.
     *
     * This is **not** a desktop-side revoke: the pairing-management surface is
     * unreachable from a paired remote device, so the desktop keeps listing this
     * phone until the user removes it there. The settings UI says so explicitly.
     */
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
     * ### Why this is the only call that touches an un-gated surface
     *
     * The `/api/pair` family is physically local to the desktop and answers only on
     * its loopback origin; the same path under `/remote` returns 403 even when the
     * caller is an authenticated device. That is not a limitation to work around —
     * `accept` *is* the act of pairing, and the desktop's QR hands the phone a
     * single-use token precisely so this one unauthenticated call can be legitimate.
     * Everything afterwards goes through `/remote` with the issued device id.
     *
     * ### Failure handling
     *
     * Three distinct failures are kept distinct in the message: an unparsable link,
     * a token the desktop refused (with its own error code), and a transport error.
     * Collapsing them would leave the user guessing whether to retry or re-scan.
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

    /**
     * Re-read the session list from the desktop.
     *
     * Note what this does *not* do on failure: it does not clear [sessions]. A
     * transient disconnect must not look like "all your sessions were deleted",
     * so the stale list stays on screen behind an error banner. The loading flag
     * is cleared in both branches so the refresh control always comes back.
     *
     * [SessionApi.list] already returns [SessionSummary] rows, so this is a direct
     * hand-over rather than a second mapping pass — one place decides what a wire
     * row means, and it is the protocol layer.
     */
    fun refreshSessions(showSpinner: Boolean = true) {
        val api = api ?: return
        // 后台刷新（重连成功后、收到 `api-session/added` 时）不翻转顶栏的
        // 刷新图标。
        //
        // 原来不分场合地置 `sessionsLoading = true`，顶栏在图标与转圈之间来回
        // 切换 —— 而 `api-session/added` 在电脑端每次新建/加载会话时都会来一发，
        // 用户看到的就是"顶部有个东西莫名其妙闪一下"。只有用户自己点刷新才该有
        // 可见的进度反馈。
        if (showSpinner) _state.update { it.copy(sessionsLoading = true) }
        scope.launch {
            try {
                val sessions = api.list()
                _state.update { it.copy(sessions = sessions, sessionsLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(sessionsLoading = false) }
                onConnectionFailure(e)
            }
        }
    }

    /**
     * Create a session on the desktop, then open it.
     *
     * The order matters: [refreshSessions] runs before [openSession] so the list
     * behind the chat screen already contains the new session when the user
     * navigates back. If creation throws, no session exists and the error is
     * surfaced rather than swallowed — a silent failure here would leave the user
     * staring at an unchanged list.
     *
     * @param cwd the desktop working directory the new file belongs to, or `null`
     *   to accept whatever default the desktop picks. Supplying it is what makes
     *   the phone's "新建文件" land in the folder the user chose instead of
     *   scattering new sessions into an invisible default.
     */
    fun newSession(cwd: String? = null) {
        val api = api ?: return
        scope.launch {
            try {
                val sessionId = api.create(cwd = cwd?.takeIf { it.isNotBlank() })
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

    /**
     * Subscribe to one session and replay its history.
     *
     * ### Why `activeSessionId` is checked on every frame
     *
     * Cancelling a coroutine does not instantly stop an OkHttp reader thread from
     * having already queued a frame. A fast back-and-forth between two sessions
     * could therefore deliver a frame belonging to the previous session into the
     * new one — the classic "two conversations get mixed up" bug. The
     * `activeSessionId != sessionId` guard drops those late frames instead of
     * rendering them into the wrong transcript.
     *
     * ### Why the chat state is reset here, not merged
     *
     * Opening a session must show *that* session's history. Merging into the
     * previous state would leak bubbles across conversations, so a fresh
     * [ChatState] is built and the snapshot then fills it.
     *
     * The last-opened session is persisted so a process restart lands the user
     * back where they were, which is what makes the "background / reopen" case
     * in the acceptance criteria work.
     *
     * @param keepContent 只在自动重连后重订阅时为 `true`：保留已经渲染的转写，
     *   只重新接上增量流。为 `false` 时（用户点进另一个会话）必须重置 —— 把
     *   上一个会话的气泡留在屏幕上是更严重的错误。
     */
    fun openSession(sessionId: String, keepContent: Boolean = false) {
        val api = api ?: return
        // 换会话时必须重置；同一个会话重订阅时保留。
        val sameSession = _state.value.chat.sessionId == sessionId
        val preserve = keepContent && sameSession
        chatJob?.cancel()
        followHandle?.cancel()
        followHandle = null
        activeSessionId = sessionId
        if (!preserve) {
            // A queue from a previous conversation describes bubbles that are no
            // longer on screen; keeping it would retag the wrong bubble.
            pendingUserTexts.clear()
        }
        // Both fields move together: `openSessionId` drives navigation (what the
        // user sees), `chat.sessionId` drives data (whose history is loaded).
        _state.update { st ->
            st.copy(
                openSessionId = sessionId,
                // `loadingHistory = true` until the snapshot arrives: an empty
                // transcript here means "not answered yet", not "no messages".
                chat = if (preserve) {
                    // 保留内容时不重开一个空 ChatState，否则屏幕会先闪白再长回来。
                    // 只把"正在读"的标记按当前是否已有内容决定：已有内容就不该
                    // 再显示"正在读取电脑上的记录"。
                    st.chat.copy(
                        sessionId = sessionId,
                        loadingHistory = st.chat.items.isEmpty() && st.chat.streamingText.isEmpty(),
                    )
                } else {
                    ChatState(sessionId = sessionId, loadingHistory = true)
                },
            )
        }
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
                    _state.update {
                        it.copy(
                            chat = it.chat.copy(
                                // Stop the spinner on failure too: a spinner that
                                // never resolves is indistinguishable from a hang.
                                loadingHistory = false,
                                error = describe(e),
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Decode one followed-session frame and fold it into the chat snapshot.
     *
     * Three frame shapes are possible and each is handled distinctly:
     * `snapshot` (the backlog, delivered once), `event` (one record from the
     * session log), and the assistant streaming frames. `Raw` is anything the
     * decoder did not claim — dropped rather than logged per-frame, since a busy
     * turn produces hundreds of them.
     *
     * The snapshot **replaces** [ChatState.items] rather than appending, because it
     * is a full re-baseline of history; appending would duplicate everything on
     * every reconnect.
     */
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
                            // The snapshot is the backlog; once it lands the
                            // transcript is authoritative and the spinner must go.
                            loadingHistory = false,
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

    /**
     * Handle one session-log event.
     *
     * This `when` is the whole UI state machine for a turn, so the two branches
     * worth reading are `turn/start` and `turn/end`:
     *
     *  - `turn/start` is the *only* thing that promotes the chat to `Running`.
     *  - `turn/end` is the *only* thing that resolves the stop lifecycle, and it
     *    reads `reason.kind` to distinguish a normal finish from a cancellation or
     *    a failure. Crucially, `turn/end` promotes a pending `Requested` (or a
     *    `Requested` that the watchdog already downgraded to `Unconfirmed`) to
     *    `Confirmed` — this is the sole path to `Confirmed` in the entire app.
     *    When the reason is `error`, the desktop's own message is copied into
     *    [ChatState.error] instead of being reduced to a red status chip.
     *    The `reason.kind` vocabulary is mapped exhaustively (see the inline note
     *    on `newStatus`) because this desktop's union is merge-extensible: a kind
     *    the phone has never seen must not silently render as "nothing happened".
     *
     * `assistant/message` de-duplicates against the already-committed streaming
     * bubble, because the same text arrives twice: once as deltas and once as the
     * final message. Without that check every answer would appear twice.
     */
    private fun handleEvent(event: FollowEvent) {
        when (event.type) {
            "turn/start" -> _state.update {
                it.copy(chat = it.chat.copy(status = ChatState.Status.Running))
            }

            "turn/end" -> {
                val data = event.data.asObjOrNull()
                val reasonObj = data?.obj("reason")
                val reason = reasonObj?.string("kind")
                // A turn that ends in `error` carries the *why* in
                // `reason.error.message`. Reporting only the status "失败" would be
                // the phone throwing away the one sentence that tells the user what
                // to fix. This is not hypothetical: a live run produced
                // `provider "newapi" model "gpt-5.2-pro" does not support reasoning
                // effort "high"` — a desktop-side setting the phone can only relay,
                // never repair, so the text has to reach the screen verbatim.
                val failureText = reasonObj?.obj("error")?.string("message")
                // The full `TurnEndReason` vocabulary, as read from the shipped
                // desktop (`turnEndToStopReason` in @deepseek-ai/dsh-acp/codec):
                // `completed`, `max-tokens`, `aborted`, `interrupted`, `blocked`,
                // `error`. Anything missing here silently degrades to `Idle`, which
                // is why `interrupted` is mapped explicitly: it is what a plain
                // user cancel looks like, and a live session ended with exactly that
                // reason. `max-tokens` is a *finished* turn that merely ran out of
                // budget, so it is `Done` rather than a failure. The `else` branch
                // stays because the union is merge-extensible upstream.
                val endedByUser = reason == "cancelled" || reason == "aborted" || reason == "interrupted"
                val newStatus = when (reason) {
                    "completed", "max-tokens" -> ChatState.Status.Done
                    "cancelled", "aborted", "interrupted" -> ChatState.Status.Cancelled
                    "failed", "error", "blocked" -> ChatState.Status.Failed
                    else -> ChatState.Status.Idle
                }
                val wasStopping = _state.value.chat.stopState
                commitStreaming()
                _state.update { st ->
                    st.copy(
                        chat = st.chat.copy(
                            status = newStatus,
                            // The Host reporting the turn over is the only proof
                            // that a cancel request actually took effect.
                            stopState = when (wasStopping) {
                                ChatState.StopState.Requested,
                                ChatState.StopState.Unconfirmed,
                                -> ChatState.StopState.Confirmed
                                else -> wasStopping
                            },
                            error = failureText ?: st.chat.error,
                        )
                    )
                }
                scope.launch {
                    val s = _state.value.settings
                    // Only notify on an outcome the user did not ask for: a turn the
                    // user interrupted themselves needs no "it finished" banner.
                    if (s.notifyOnDone && !endedByUser) {
                        notifier.taskFinished(
                            sessionId = _state.value.chat.sessionId.orEmpty(),
                            title = _state.value.chat.title ?: "会话",
                            success = newStatus == ChatState.Status.Done,
                        )
                    }
                }
                // 后台刷新：用户没有点刷新，顶栏不该转圈。
                refreshSessions(showSpinner = false)
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
                // Strip our own reply-language directive back out: the content
                // array carries both the user's sentence and the directive we
                // appended, and only the former is something the user typed.
                // Without this, the transcript shows a sentence nobody wrote — and
                // the de-duplication below would never match, reproducing the
                // "one message sent, two shown" bug.
                val text = PromptDirectives.contentTextWithoutDirectives(data["content"])
                if (text.isBlank()) return
                // Reconcile the echo with the optimistic bubble painted in `send`.
                //
                // Order matters: peek the head of the FIFO *before* touching state,
                // so this stays a single atomic decision. On an exact text match we
                // consume it and retag the bubble in place; otherwise this is a
                // message we never sent from this screen (a turn authored on the
                // desktop, or a replay after a reconnect) and it is appended.
                //
                // Matching on the head only — not on "any pending text" — is what
                // makes sending "你好" twice behave correctly: the two prompts
                // produce two echoes and two queue entries, consumed in order.
                val echoedLocally = pendingUserTexts.firstOrNull() == text
                if (echoedLocally) pendingUserTexts.removeFirst()
                _state.update { st ->
                    if (st.chat.items.any { it.key == "u-${event.seq}" }) {
                        // A re-delivered event (reconnect replay): already present.
                        st
                    } else if (echoedLocally) {
                        // Retag the optimistic bubble with the stable event key so a
                        // future snapshot re-baseline can dedup it, and so ordering
                        // matches the desktop's own seq order.
                        var retagged = false
                        val items = st.chat.items.map { item ->
                            if (!retagged && item.role == ChatItem.Role.User &&
                                item.key.startsWith("local-") && item.text == text
                            ) {
                                retagged = true
                                item.copy(key = "u-${event.seq}")
                            } else {
                                item
                            }
                        }
                        if (retagged) {
                            st.copy(chat = st.chat.copy(items = items))
                        } else {
                            // The optimistic bubble is already gone (snapshot took
                            // over). Trust the snapshot; do not append a second copy.
                            st
                        }
                    } else {
                        st.copy(
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

    /**
     * Move the streaming buffer into a permanent bubble.
     *
     * Called on `turn/end` and on the stream's `end` frame. Keyed by
     * `System.nanoTime()` because the stream has no record seq of its own; the
     * value only has to be unique within the list, not meaningful.
     *
     * A blank buffer is a no-op so a cancelled-before-output turn does not add an
     * empty bubble.
     */
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

    /**
     * Submit a prompt to the desktop.
     *
     * ### The two halves, and why the bubble is optimistic but the status is not
     *
     * The user bubble is appended **immediately**. Waiting for a round trip before
     * showing the user's own words would feel broken on a slow tunnel, and there is
     * no security claim in rendering text the user just typed.
     *
     * The *status*, however, is not optimistic: it moves to [ChatState.Status.Queued]
     * and only a `{accepted:true}` reply validates it. If the desktop refuses the
     * prompt, the status falls back to `Idle` with an explicit
     * "电脑未接受这条任务" error — the bubble stays, because the user did type it, but
     * the app never implies the desktop is working on something it rejected.
     *
     * @param mode `Queue` waits for the current turn to finish; the wire value is
     *   `request.mode`.
     */
    fun send(text: String, mode: PromptMode = PromptMode.Queue) {
        val api = api ?: return
        val sessionId = _state.value.chat.sessionId ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        // The reply-language directive is appended as a **separate content part**,
        // never spliced into `body`. Two consequences that matter here:
        //
        //   * `body` is what the optimistic bubble below shows, so the user sees
        //     exactly what they typed, not what we added;
        //   * the echo matching further down compares against `body` only, so the
        //     directive can never break de-duplication.
        //
        // See `PromptDirectives` for why the directive exists at all.
        val directives: List<String> = if (_state.value.settings.simplifiedChinese) {
            listOf(PromptDirectives.SIMPLIFIED_CHINESE)
        } else {
            // Explicit element type: without it Kotlin cannot infer `T` from the
            // bare `emptyList()` in the else branch of an if-expression.
            emptyList()
        }
        // Show the bubble immediately, but keep the status honest: the send is
        // only acknowledged once the gateway answers `{accepted:true}`.
        //
        // ### Why the de-duplication is a queue of texts, not a key
        //
        // The desktop echoes every user turn back through the follow stream as a
        // `user/message` event. That echo is authoritative — but this bubble was
        // painted a round trip earlier for responsiveness, so something has to
        // reconcile the two or the message renders twice.
        //
        // An earlier attempt keyed the local bubble on `text.hashCode()` and
        // matched on that. It is the wrong tool: two different texts can share a
        // hash (collision), and sending the same text twice — "你好", then "你好"
        // again — collides with itself, so the second bubble either vanished or
        // the first one never got reconciled.
        //
        // A FIFO of the exact strings we have optimistically painted matches the
        // way the desktop actually behaves: echoes come back in the order the
        // prompts were accepted. `user/message` pops the head when the text
        // matches, which is a real identity check rather than a guess.
        pendingUserTexts.addLast(body)
        val localKey = "local-${nextLocalSeq++}"
        _state.update { st ->
            st.copy(
                chat = st.chat.copy(
                    items = st.chat.items + ChatItem(
                        key = localKey,
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
                val accepted = api.prompt(sessionId, body, mode, extraTextParts = directives)
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
     * ### The four-step contract, and why each step exists
     *
     * 1. Move to [ChatState.StopState.Requested] *before* the call, so a re-tap is
     *    blocked and the UI can honestly say "waiting for the desktop".
     * 2. Send `session/cancel`. A `false` reply or a throw means we could not even
     *    deliver the request → [ChatState.StopState.Unconfirmed], which reads as
     *    "may still be running".
     * 3. On acceptance, poll for up to **15 s** waiting for the state machine to
     *    reach `Confirmed`. The promote happens in [handleEvent]'s `turn/end`
     *    branch, not here.
     * 4. If the deadline passes while still `Requested`, downgrade to
     *    [ChatState.StopState.Unconfirmed].
     *
     * ### Why the watchdog is not a timeout on the RPC
     *
     * The RPC already returned successfully — the desktop *accepted* the cancel.
     * What has not arrived is the observable consequence. The watchdog therefore
     * measures "how long do we wait for evidence", not "how long do we wait for a
     * network call". 15 s is generous enough for a turn to unwind cleanly on a slow
     * machine and short enough that a user is not left staring at an ambiguous
     * spinner.
     *
     * The polling loop reads `_state` rather than sharing a channel, which keeps
     * this function self-contained and means the `turn/end` handler needs no
     * knowledge of whoever is waiting.
     *
     * A `Confirmed` that arrives *after* the timeout is still possible — the
     * `turn/end` branch also promotes from `Unconfirmed`, so a late truth corrects
     * the pessimistic banner rather than being lost.
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

    /**
     * Clear the dismissable error strip on the chat screen.
     *
     * Only the chat-local `error` is cleared. A connection-level failure lives on
     * [ConnectionState] and must stay visible until the link is actually restored —
     * letting the user dismiss it would be letting them dismiss the fact that they
     * are disconnected.
     */
    fun dismissChatError() {
        _state.update { it.copy(chat = it.chat.copy(error = null)) }
    }

    // -------------------------------------------------------------- approval

    /**
     * Subscribe to the forwarded-event carrier (`$events`).
     *
     * This one socket carries two different things, which is why the loop
     * branches:
     *  - **waterfall frames** — approval requests, which must be answered;
     *  - **`emit` frames** — ordinary notifications such as `api-session/status`
     *    (the authoritative "is this session running" signal) and
     *    `api-session/added`.
     *
     * ### Why a disconnect here is reported rather than retried
     *
     * The gateway's own documentation is explicit that ordinary notifications are
     * not replayed after a reconnect. So if this stream dies silently, the phone
     * would keep showing a stale status forever while believing it was connected.
     * Surfacing the failure is the only honest option; the user (or the next
     * foreground event) decides whether to reconnect.
     *
     * The `phase == Connected` guard prevents a failure during teardown from
     * overwriting a state that is already being replaced.
     */
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

    /**
     * Handle one forwarded notification.
     *
     * `api-session/status` is `[sessionId, running]` and is the authoritative
     * source for the session-list running dot. Using it (rather than inferring
     * from our own prompt/cancel calls) means the list stays correct even when
     * the turn was started or stopped from the desktop's own window.
     *
     * `api-session/added` only triggers a re-list, because the payload does not
     * carry enough to construct a full row.
     */
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
            "api-session/added" -> refreshSessions(showSpinner = false)
        }
    }

    /**
     * Record an approval request that arrived on the `$events` waterfall.
     *
     * Two things happen, and both matter:
     *
     *  - it is **appended to `approvals.pending`** — a queue, not a single slot,
     *    because the desktop can raise a second request while the first is still
     *    unanswered;
     *  - it becomes the `chat.approval` card **only if no card is already showing**
     *    (`?: approval`). Overwriting a visible card would silently swap the thing
     *    the user is about to tap.
     *
     * The chat status is forced to [ChatState.Status.AwaitingApproval] because that
     * is exactly what the desktop is doing: the turn is blocked on a human. The UI
     * uses this to stop claiming the task is "running".
     *
     * Notification dispatch is fire-and-forget on `scope`: a failed notification
     * must never interfere with the in-app card, which is the authoritative
     * surface.
     */
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

    /**
     * Remove the pending approval locally and deliver the decision.
     *
     * ### Order: optimistic removal, then send
     *
     * The card is removed from the snapshot *before* the network call. Unlike a
     * prompt, an approval is not something the user can meaningfully "retry" from
     * the same card — the desktop's request may already have expired — so leaving a
     * dead card on screen while a slow tunnel round-trips would be worse than
     * removing it and reporting a failure. If delivery fails, an error is set; the
     * approval itself is gone either way, which matches reality (the desktop's
     * waterfall is waiting on a client that, here, no longer will answer).
     *
     * ### Why `Running` is set on answer
     *
     * Answering unblocks the desktop's turn, so the turn resumes. `turn/end` will
     * still be the authority on how it ends.
     *
     * @param allow `true` → `allowed-once`, `false` → `rejected`. There is no third
     *   outcome; see [ApprovalRequest.allowOnce] and [ApprovalRequest.reject].
     */
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

    /**
     * Answer whichever approval is currently at the front of the queue.
     *
     * Used by the in-app buttons, which act on "the card I am looking at". The
     * queue head is preferred over `chat.approval` so the answer always goes to a
     * request that is genuinely still pending — if a card somehow outlived its
     * queue entry, answering it would address a desktop request that no longer
     * exists.
     */
    fun answerCurrentApproval(allow: Boolean) {
        val pending = _state.value.approvals.current
            ?: _state.value.chat.approval
            ?: return
        answerApproval(pending, allow)
    }

    /**
     * Answer an approval by event id — the entry point used by the notification.
     *
     * @return `false` when no pending approval carries this id, which is exactly the
     *   case where the process was restarted after the approval was raised. The
     *   caller must surface that to the user rather than reporting success; see
     *   [dev.dshpocket.notify.ApprovalActionReceiver].
     */
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
     * socket per opened session, but the last session id stays in settings so a
     * *deliberate* reopen can land near where the user was.
     *
     * Both navigation fields are cleared **synchronously and together**. Doing it
     * in two steps would leave one frame where `openSessionId` is null while
     * `chat.sessionId` still points at the old session, and any UI that falls
     * back from the first to the second would briefly render the chat again —
     * the visible symptom being a screen that will not let you leave.
     */
    fun closeSession() {
        chatJob?.cancel()
        chatJob = null
        followHandle?.cancel()
        followHandle = null
        activeSessionId = null
        pendingUserTexts.clear()
        _state.update { it.copy(openSessionId = null, chat = ChatState()) }
    }

    // ----------------------------------------------------------------- misc

    fun setNotifyOnDone(v: Boolean) = scope.launch { settings.setNotifyOnDone(v) }
    fun setNotifyOnApproval(v: Boolean) = scope.launch { settings.setNotifyOnApproval(v) }
    fun setNotifyOnDisconnect(v: Boolean) = scope.launch { settings.setNotifyOnDisconnect(v) }
    fun setKeepScreenAwake(v: Boolean) = scope.launch { settings.setKeepScreenAwake(v) }

    /**
     * Toggle the "reply in Simplified Chinese" directive.
     *
     * The desktop model chooses its own output language, and the Remote API has
     * no setting for it — so the only honest lever is to attach a directive to
     * each prompt. Off means byte-exact passthrough with nothing added.
     */
    fun setSimplifiedChinese(v: Boolean) = scope.launch { settings.setSimplifiedChinese(v) }

    /**
     * Collapse a throwable into something worth showing a user.
     *
     * The two cases are genuinely different and the user needs to tell them apart:
     * a [RemoteCallException] means the desktop **answered and refused** (so the
     * gateway's own error code and message are the most useful thing to show), while
     * anything else is a transport-level failure where the message — or, failing
     * that, the exception's simple class name — is all we have. Never returns an
     * empty string, so an error strip can never render blank.
     */
    private fun describe(e: Throwable): String = when (e) {
        is RemoteCallException -> "${e.error.code}: ${e.error.message}"
        else -> e.message ?: e::class.java.simpleName
    }

    /**
     * The live gateway, or `null` when disconnected.
     *
     * Exposed for the foreground service, which needs to know whether it still has
     * a client to justify staying alive. Callers must not cache the result: the
     * client is replaced wholesale on every (re)connect, and a stale reference
     * would be a client that is already shut down.
     */
    fun currentGateway(): GatewayClient? = client

    /** Resume the most recent session, if the user had one and it still exists. */
    fun resumeLastSession(lastSessionId: String?) {
        if (lastSessionId.isNullOrBlank()) return
        if (_state.value.sessions.any { it.id == lastSessionId }) openSession(lastSessionId)
    }
}
