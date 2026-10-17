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
 * logs that id (see [DeviceStore]).
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

    /** One logical stream opened on the shared mux carrier. */
    inner class StreamHandle internal constructor(
        internal val streamId: String,
        internal val endpoint: String,
    ) {
        internal val inbox = Channel<Frame>(Channel.UNLIMITED)

        /** Host items until `end`, `error`, or cancellation. */
        val items: Flow<JsonElement?> = flow {
            for (frame in inbox) {
                when (frame) {
                    is Frame.Item -> emit(frame.value)
                    is Frame.End -> return@flow
                    is Frame.Error -> throw RemoteCallException(frame.error)
                }
            }
        }

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

    internal sealed interface Frame {
        class Item(val value: JsonElement?) : Frame
        data object End : Frame
        class Error(val error: RemoteError) : Frame
    }

    private val streams = ConcurrentHashMap<String, StreamHandle>()
    private val pendingOpens = ConcurrentHashMap<String, StreamHandle>()

    @Volatile private var socket: WebSocket? = null

    @Volatile private var muxReady = false

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

    private fun muxUrl(): HttpUrl = baseUrl.newBuilder()
        .addPathSegment("remote").addPathSegment("api").addPathSegment("remote.mux")
        .addQueryParameter("device", deviceId)
        .build()

    /** Ensure the mux WebSocket is connected; safe to call repeatedly. */
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

    private fun captureEventsIdentity(value: JsonElement?) {
        val o = value?.asObjOrNull() ?: return
        if (o.string("type") != "ready") return
        eventsClientId = o.string("clientId")
        hostHome = o.obj("host")?.string("home")
    }

    private fun sendFrame(frame: JsonObject) {
        val ws = socket ?: throw RemoteCallException(
            RemoteError("gateway/mux-closed", "Remote mux is not connected")
        )
        if (!ws.send(frame.toString())) {
            throw RemoteCallException(RemoteError("gateway/mux-closed", "Remote mux rejected the frame"))
        }
    }

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

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            muxReady = false
            socket = null
            val failure = RemoteError(
                code = "gateway/mux-failed",
                message = t.message ?: "Remote mux failed (HTTP ${response?.code ?: 0})",
            )
            for (h in streams.values + pendingOpens.values) {
                h.inbox.trySend(Frame.Error(failure))
                h.inbox.close()
            }
            streams.clear()
            pendingOpens.clear()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            muxReady = false
            socket = null
            for (h in streams.values + pendingOpens.values) {
                h.inbox.trySend(Frame.Error(RemoteError("gateway/mux-closed", "Remote mux closed: $reason")))
                h.inbox.close()
            }
            streams.clear()
            pendingOpens.clear()
        }
    }

    fun close() {
        socket?.close(1000, "client closing")
        socket = null
        muxReady = false
    }

    /** Convenience: open the forwarded-event source used for approvals and pings. */
    suspend fun openEvents(): StreamHandle = openStream("\$events")

    /** Answer one forwarded waterfall event (approval decision). */
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
        scope.launch {
            runCatching { ensureMux() }
        }
    }
}
