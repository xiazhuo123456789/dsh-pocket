package dev.dshpocket.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Phone-side client for the Harness typert gateway.
 *
 * Two carriers, exactly as the Host implements them:
 *  - unary calls: `POST /remote/api/<endpoint>` with a `client-request` envelope;
 *  - streaming calls: one multiplexed WebSocket at `/remote/api/remote.mux`
 *    carrying `open`/`item`/`end`/`cancel` frames keyed by `streamId`.
 *
 * Every request authenticates with the paired device id header. The Host treats
 * a paired device as a full-control credential, so this class never persists or
 * logs that id (see [dev.dshpocket.data.CredentialStore]).
 *
 * ### Where these wire shapes come from
 *
 * None of this is a published public specification. The frame and envelope shapes
 * were read out of the desktop software itself and then confirmed against a live
 * process:
 *  - `@deepseek-ai/dsh-api-gateway` (shipped inside the desktop app bundle) for
 *    the envelope, the mux frames, and the `$events` forwarding model;
 *  - the generated `lib/typert.remote-client.js` of each controller package for
 *    per-method argument names;
 *  - `@linxin666/dsh-remote-web-ui` `src/remote-api.ts` for the `/remote` prefix,
 *    the device header, and the `?device=` query used on WebSocket upgrades.
 *
 * See `docs/sources.md` for the full provenance table and
 * `tests/conformance.mjs` for the live re-check of this contract. The details are
 * version-specific observations, not a stable API promise.
 *
 * ### Failure model
 *
 * There is no automatic reconnect in this class. A dead carrier surfaces as a
 * [RemoteCallException] on every in-flight stream, and it is the ViewModel's job
 * to decide whether to re-dial. Hiding a reconnect here would make "the socket is
 * gone" indistinguishable from "the request is still running", which is the exact
 * ambiguity this project is trying to avoid.
 */
class GatewayClient(
    val baseUrl: HttpUrl,
    private val deviceId: String,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = defaultHttp(),
) {
    companion object {
        const val DEVICE_HEADER = "x-dsh-remote-device"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * The shared HTTP stack.
         *
         * Each timeout is chosen for a different job:
         *
         *  - `connectTimeout(15s)` — long enough for a slow mobile radio to come up,
         *    short enough that a wrong/unreachable host fails while the user still
         *    remembers what they typed.
         *  - `readTimeout(0)` — **no** read timeout, because this same client drives
         *    the multiplexed WebSocket. A read timeout would kill a healthy idle
         *    socket that is simply waiting for the next turn event.
         *  - `writeTimeout(30s)` — prompts can be large (long pasted text), so
         *    writing is given more room than connecting.
         *  - `pingInterval(20s)` — keeps the socket alive through NAT/carrier
         *    idle timeouts *and* is how a dead link is discovered: without it a
         *    half-open TCP connection can sit there looking connected forever.
         *  - `retryOnConnectionFailure(true)` — safe here only because OkHttp
         *    restricts it to failures where the request certainly never left.
         */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // streaming: no read timeout
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /**
         * A client that never opens a socket.
         *
         * Decoding (`decodeFrame`, `assistantDelta`, `decodeApproval`) is pure and
         * is where the wire format is pinned, so the protocol tests exercise it
         * without a Host. The scope is already cancelled, so the warm-up launch
         * in `init` never runs.
         */
        fun stub(): GatewayClient = GatewayClient(
            baseUrl = "http://stub.invalid".toHttpUrl(),
            deviceId = "stub",
            scope = CoroutineScope(Job().apply { cancel() }),
        )
    }

    private val rpcSeq = AtomicLong()

    // ---------------------------------------------------------------- unary

    /**
     * Invoke one unary Remote method.
     *
     * @param endpoint canonical `namespace/method`, e.g. `session/list`.
     * @param args the *wire* argument object; parameter names come from the
     *   Host descriptor and are not always the source-level names (notably
     *   `session/list` uses `_request` while `session/create` uses `request`).
     *
     * The Host validates the argument set strictly: a wrong or extra field is
     * rejected with `gateway/arguments-invalid` and a message naming the exact
     * mismatch. That strictness is what pinned the names used in `SessionApi`;
     * it is also why this method takes a pre-built [JsonObject] rather than
     * guessing from reflection.
     *
     * @throws RemoteCallException for a Host-side error, a transport failure, or
     *   a malformed envelope. A `200` response with `ok:false` is still an error.
     */
    suspend fun call(endpoint: String, args: JsonObject = JsonObject(emptyMap())): JsonElement? {
        val rpcId = "p${rpcSeq.incrementAndGet()}-${UUID.randomUUID()}"
        val envelope = buildJsonObject {
            put("type", "client-request")
            put("rpcId", rpcId)
            put("method", endpoint)
            put("payload", buildJsonObject { put("args", args) })
        }
        val url = baseUrl.newBuilder()
            .addPathSegment("remote").addPathSegment("api")
            .apply { endpoint.split('/').forEach { addPathSegment(it) } }
            .build()
        val request = Request.Builder()
            .url(url)
            .header(DEVICE_HEADER, deviceId)
            .post(envelope.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response: Response = http.newCall(request).execute()
        response.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful && body.isBlank()) {
                throw RemoteCallException(
                    RemoteError("http/${r.code}", "HTTP ${r.code} from ${r.request.url.encodedPath}")
                )
            }
            val root = Json.lenient.parseToJsonElement(body).jsonObject
            val result = root["result"]?.jsonObject
                ?: throw RemoteCallException(RemoteError("gateway/envelope", "missing result envelope"))
            return when (result.bool("ok")) {
                true -> result["value"]
                else -> {
                    val err = result["error"]?.jsonObject
                    throw RemoteCallException(
                        RemoteError(
                            code = err?.string("code") ?: "gateway/unknown",
                            message = err?.string("message") ?: "unknown gateway error",
                            details = err?.obj("details"),
                        )
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- streaming

    /**
     * One logical stream opened on the shared mux carrier.
     *
     * The Host can multiplex many streams over a single WebSocket, so a handle is
     * a routing key plus a private queue — not a socket. Frames arrive on the
     * listener thread and are handed over through [inbox]; [items] is the
     * suspending view that callers actually consume.
     *
     * The queue is [Channel.UNLIMITED] deliberately. A bounded queue would let a
     * slow UI consumer block the OkHttp reader thread, which would stall the ping
     * response and make the Host kill the whole mux for an unrelated reason.
     * Backpressure is instead applied at the source: the ViewModel coalesces
     * assistant deltas into one text buffer rather than storing every chunk.
     */
    inner class StreamHandle internal constructor(
        internal val streamId: String,
        internal val endpoint: String,
    ) {
        internal val inbox = Channel<Frame>(Channel.UNLIMITED)

        /**
         * Host items until `end`, `error`, or cancellation.
         *
         * Terminates normally on [Frame.End], throws on [Frame.Error]. Callers can
         * therefore use ordinary `for`/`collect` and treat an exception as a real
         * failure rather than as end-of-stream.
         */
        val items: Flow<JsonElement?> = flow {
            for (frame in inbox) {
                when (frame) {
                    is Frame.Item -> emit(frame.value)
                    is Frame.End -> return@flow
                    is Frame.Error -> throw RemoteCallException(frame.error)
                }
            }
        }

        /**
         * Ask the Host to stop this stream and drop the local queue.
         *
         * Sends `cancel` before closing so the Host can release server-side work;
         * closing first would leave the Host producing items nobody reads until
         * it notices the socket is gone.
         */
        fun cancel() {
            sendFrame(
                buildJsonObject {
                    put("type", "cancel")
                    put("streamId", streamId)
                }
            )
            inbox.close()
        }
    }

    /**
     * One multiplexed stream, as seen by a caller.
     *
     * `Item` and `End` are the normal lifecycle; `Error` is the Host reporting a
     * failure *for this stream only* (the socket stays up). Keeping the error on
     * the stream — rather than surfacing it as a socket-level exception — is what
     * lets one failed follow call coexist with a healthy mux carrying other streams.
     */
    internal sealed interface Frame {
        class Item(val value: JsonElement?) : Frame
        data object End : Frame
        class Error(val error: RemoteError) : Frame
    }

    private val streams = ConcurrentHashMap<String, StreamHandle>()

    /**
     * Streams whose `open` frame was sent but which have not produced their first
     * item yet.
     *
     * This two-map split exists because the Host only starts routing frames for a
     * `streamId` after it has accepted the `open`. Until the first frame arrives
     * there is no way to tell "still starting" from "silently dropped", so the
     * handle lives here and is promoted on first contact. A stream that never
     * produces a first frame simply stays pending, which surfaces as a timeout at
     * the caller instead of a false success.
     */
    private val pendingOpens = ConcurrentHashMap<String, StreamHandle>()

    @Volatile private var socket: WebSocket? = null

    @Volatile private var muxReady = false

    /**
     * 承载一旦断开就通知外面 —— 让上层能真正重建连接。
     *
     * ### 为什么必须回调，而不是只把 `muxReady` 置 false
     *
     * `onFailure`/`onClosed` 只做三件事：置 `muxReady = false`、把错误塞进所有
     * 已开流的队列、清空流表。**没有任何人负责重连。** 于是手机切到后台、隧道
     * 抖动、或桌面端重启之后，界面会永久停在一行
     * `gateway/mux-failed: Remote mux failed (HTTP 0)` 上，只有手动去设置页点
     * 「重新连接」才能恢复 —— 而用户根本不知道该去点那个按钮。
     *
     * 更糟的是状态条还会继续显示「运行中」：`api-session/status` 是唯一的状态
     * 来源，链路断了就再也收不到它，最后一次「在跑」的读数被冻结在界面上。这
     * 等于用旧数据假装知道当前状态，正是本项目最不能容忍的那种谎。
     *
     * 所以这里把"承载死了"这件事上报给 [PocketViewModel]，由它决定退避重连，
     * 并把状态降级为「不知道」。
     */
    var onCarrierLost: ((RemoteError) -> Unit)? = null

    /**
     * Identity the Host assigned to this `$events` subscriber. It is required to
     * answer waterfall events (`$events/result` correlates on `clientId`), and it
     * is only known after the `{type:"ready"}` opening frame.
     */
    @Volatile var eventsClientId: String? = null
        private set

    @Volatile var hostHome: String? = null
        private set

    private val muxLock = Any()

    /**
     * The one multiplexed carrier URL.
     *
     * Built with [HttpUrl.Builder] rather than string concatenation so a base URL
     * with a trailing slash (or an existing path, for a reverse-proxied install)
     * still produces a correct `/remote/api/remote.mux`. The device id goes in the
     * **query string** here, not a header, because the WebSocket upgrade cannot
     * reliably carry the custom credential header from every client stack.
     */
    private fun muxUrl(): HttpUrl = baseUrl.newBuilder()
        .addPathSegment("remote").addPathSegment("api").addPathSegment("remote.mux")
        .addQueryParameter("device", deviceId)
        .build()

    /**
     * Ensure the mux WebSocket is connected; safe to call repeatedly.
     *
     * The double-checked lock matters: several callers can race here (the warm-up
     * in `init`, `openEvents`, and a `session/follow`), and opening two sockets
     * would split streams across carriers that each think they own the id space.
     *
     * The 20 s deadline is generous on purpose — a phone waking from doze over a
     * quick tunnel can take several seconds to complete a TLS handshake — but it
     * is finite, because an unbounded wait is indistinguishable from a hang.
     */
    suspend fun ensureMux() {
        if (muxReady && socket != null) return
        synchronized(muxLock) {
            if (muxReady && socket != null) return
            val request = Request.Builder().url(muxUrl()).build()
            val ws = http.newWebSocket(request, MuxListener())
            socket = ws
        }
        // Wait for the carrier to reach OPEN before letting callers `open`.
        val deadline = System.currentTimeMillis() + 20_000
        while (!muxReady) {
            if (System.currentTimeMillis() > deadline) {
                throw RemoteCallException(RemoteError("gateway/mux-timeout", "Remote mux did not open"))
            }
            kotlinx.coroutines.delay(25)
        }
    }

    /**
     * Open one streaming Remote method.
     *
     * @param endpoint canonical endpoint, or the internal `$events` source.
     *
     * Returns as soon as the `open` frame is handed to the socket — it does not
     * wait for the first item. Callers that need proof the stream is live must
     * consume [StreamHandle.items] and check that a first frame actually arrives
     * (that is what `session/follow`'s `snapshot` and `$events`'s `ready` frame
     * are for).
     */
    suspend fun openStream(endpoint: String, args: JsonObject = JsonObject(emptyMap())): StreamHandle {
        ensureMux()
        val streamId = UUID.randomUUID().toString()
        val handle = StreamHandle(streamId, endpoint)
        pendingOpens[streamId] = handle
        val frame = buildJsonObject {
            put("type", "open")
            put("streamId", streamId)
            put("endpoint", endpoint)
            put("payload", buildJsonObject { put("args", args) })
        }
        sendFrame(frame)
        return handle
    }

    /**
     * Read the `clientId` out of the `$events` opening frame.
     *
     * This is a genuine ordering hazard, so it is handled explicitly rather than
     * assumed: the Host will not accept a `$events/result` reply until the
     * subscriber has announced itself with `{type:"ready"}`, and that frame is the
     * only place the id appears. [answerEvent] therefore refuses to guess and
     * throws `gateway/events-not-ready` instead of sending a reply that would be
     * rejected with a less helpful error.
     */
    private fun captureEventsIdentity(value: JsonElement?) {
        val o = value?.asObjOrNull() ?: return
        if (o.string("type") != "ready") return
        eventsClientId = o.string("clientId")
        hostHome = o.obj("host")?.string("home")
    }

    /**
     * Send one already-built frame, or fail loudly.
     *
     * `WebSocket.send` returning `false` means the frame **did not leave** — it was
     * queued into a socket that has already closed. Treating that as success would
     * be the exact failure mode this project exists to prevent (a call that looks
     * sent but never was), so it is converted into the same error as "no socket at
     * all".
     */
    private fun sendFrame(frame: JsonObject) {
        val ws = socket ?: throw RemoteCallException(
            RemoteError("gateway/mux-closed", "Remote mux is not connected")
        )
        if (!ws.send(frame.toString())) {
            throw RemoteCallException(RemoteError("gateway/mux-closed", "Remote mux rejected the frame"))
        }
    }

    /**
     * Carrier event loop.
     *
     * Runs on OkHttp's reader thread, so every handler here must stay non-blocking:
     * items are pushed into unbounded queues and the ping/pong bookkeeping is left
     * to OkHttp (`pingInterval`). Anything that suspends or does I/O here would
     * delay the pong and provoke a Host-side disconnect after ~2 s.
     */
    private inner class MuxListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            muxReady = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = try {
                Json.lenient.parseToJsonElement(text).jsonObject
            } catch (_: Throwable) {
                return
            }
            val streamId = frame.string("streamId") ?: return
            val handle = streams[streamId] ?: pendingOpens[streamId] ?: return
            when (frame.string("type")) {
                "item" -> {
                    // First frame promotes the pending open to a live stream.
                    if (pendingOpens.remove(streamId) != null) streams[streamId] = handle
                    val value = frame["value"]
                    if (handle.endpoint == "\$events") captureEventsIdentity(value)
                    handle.inbox.trySend(Frame.Item(value))
                }
                "end" -> {
                    pendingOpens.remove(streamId)
                    streams.remove(streamId)
                    handle.inbox.trySend(Frame.End)
                    handle.inbox.close()
                }
                "error" -> {
                    pendingOpens.remove(streamId)
                    streams.remove(streamId)
                    val e = frame["error"]?.jsonObject
                    handle.inbox.trySend(
                        Frame.Error(
                            RemoteError(
                                code = e?.string("code") ?: "gateway/stream",
                                message = e?.string("message") ?: "stream error",
                                details = e?.obj("details"),
                            )
                        )
                    )
                    handle.inbox.close()
                }
            }
        }

        /**
         * Carrier failure.
         *
         * Every open stream is failed with the same error and closed, because
         * after the carrier dies none of them can make progress. Leaving them open
         * would make a follow stream look idle instead of broken — the failure has
         * to propagate to the caller so the UI can say "disconnected" rather than
         * "the desktop is thinking".
         */
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            muxReady = false
            socket = null
            val failure = RemoteError(
                code = "gateway/mux-failed",
                // `HTTP 0` 这个数字很误导：它不是"服务器返回了 0"，而是**根本没
                // 拿到 HTTP 响应**（DNS/TLS/连接被掐断/超时）。把这句话讲清楚，
                // 用户才知道该查什么，而不是去怀疑桌面端返回了错误码。
                message = t.message ?: "与电脑的实时通道断开（没有收到 HTTP 响应）",
            )
            failAllStreams(failure)
            // 上报给上层做退避重连。放在最后：先把本地状态收拾干净（流已失败、
            // 表已清空），这样重连时不会看到半死的旧句柄。
            onCarrierLost?.invoke(failure)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            muxReady = false
            socket = null
            val failure = RemoteError(
                "gateway/mux-closed",
                "与电脑的实时通道已关闭（$code${if (reason.isNotBlank()) " $reason" else ""}）",
            )
            failAllStreams(failure)
            onCarrierLost?.invoke(failure)
        }

        /**
         * 把同一个失败广播给所有已开流并关闭它们。
         *
         * 承载死了以后没有任何流还能推进。留着不关会让 follow 流看起来"空闲"而
         * 不是"坏了"，用户就分不清"电脑在思考"和"我们掉线了"。
         */
        private fun failAllStreams(failure: RemoteError) {
            for (h in streams.values + pendingOpens.values) {
                h.inbox.trySend(Frame.Error(failure))
                h.inbox.close()
            }
            streams.clear()
            pendingOpens.clear()
        }
    }

    /**
     * Tear the carrier down.
     *
     * Sends a normal-closure frame (1000) so the Host can reap the subscriber
     * immediately instead of waiting for a missed pong. Idempotent: calling it
     * twice, or on a never-connected client, is not an error.
     */
    fun close() {
        socket?.close(1000, "client closing")
        socket = null
        muxReady = false
    }

    /** Convenience: open the forwarded-event source used for approvals and pings. */
    suspend fun openEvents(): StreamHandle = openStream("\$events")

    /**
     * Answer one forwarded waterfall event (approval decision).
     *
     * @param eventId the `eventId` from the waterfall frame.
     * @param outcome one of [ApprovalRequest.allowOnce] or
     *   [ApprovalRequest.reject]. The Host accepts no other vocabulary; the
     *   remaining internal states (`cancelled`, `unavailable`) are Host-produced
     *   and cannot be asserted by a client.
     *
     * The unary reply resolves to `void` on success. A `null` return is therefore
     * the success case — do not read it as "the approval was ignored". Proof that
     * the decision landed arrives separately as an `approval/decided` event on the
     * followed session stream, which is what the UI waits for.
     *
     * @throws RemoteCallException if `$events` has not announced its `clientId`
     *   yet, or if the Host rejects the reply (for example when the request has
     *   already been settled by another subscriber).
     */
    suspend fun answerEvent(eventId: String, outcome: JsonObject) {
        val clientId = eventsClientId ?: throw RemoteCallException(
            RemoteError("gateway/events-not-ready", "The \$events source has not announced its clientId yet")
        )
        val args = buildJsonObject {
            put("clientId", clientId)
            put("eventId", eventId)
            put("outcome", outcome)
        }
        call("\$events/result", args)
    }

    init {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        // Keep the carrier warm for the lifetime of the client.
        //
        // This is an optimisation, not a requirement: `openStream` calls
        // `ensureMux` anyway. Warming up here removes a handshake's worth of
        // latency from the first user action, which matters because that action
        // is usually "open a session" right after launch.
        scope.launch {
            runCatching { ensureMux() }
        }
    }
}
