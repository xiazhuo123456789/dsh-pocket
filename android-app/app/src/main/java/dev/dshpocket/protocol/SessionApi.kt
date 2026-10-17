package dev.dshpocket.protocol

import dev.dshpocket.core.SessionSummary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Typed facade over the `session` Remote namespace of
 * `@deepseek-ai/dsh-api-session-controller`.
 *
 * ### Provenance
 *
 * The method names, argument names and result shapes below were read from the
 * controller's generated client (`lib/typert.remote-client.js`) as shipped in the
 * desktop application bundle, then confirmed by calling them against a running
 * desktop. They are not taken from any published API document, because none
 * exists for this surface. See `docs/sources.md`.
 *
 * ### The argument-name trap
 *
 * Wire-argument names are **not** uniform across the namespace:
 *
 * | endpoint | wire argument |
 * |---|---|
 * | `session/list` | `_request` |
 * | `session/create` | `request` |
 * | `session/prompt` | `request` |
 * | `session/cancel` | `request` |
 * | `session/follow` | `request` |
 *
 * Mixing them up yields `gateway/arguments-invalid` with a message that names the
 * exact mismatch, e.g. `args fields do not match the descriptor: missing
 * "request"; unexpected "_request"`. The asymmetry looks like an upstream
 * inconsistency rather than a design choice, but it is the contract, so it is
 * pinned here with a comment on every affected method.
 *
 * ### What this class does *not* do
 *
 * It performs no retries, no caching and no state derivation. Every method is a
 * thin, honest translation between Kotlin values and the wire. All decisions
 * about what a response *means* live in `PocketViewModel`, so the protocol layer
 * stays testable without a Host (see `ProtocolTest`).
 */
class SessionApi(private val client: GatewayClient) {

    /**
     * One decoded `session/follow` frame.
     *
     * `session/follow` is a journal stream: the first item is a [Snapshot]
     * containing the full history, then live [Event]s arrive. [AssistantStream]
     * carries token-by-token deltas so the UI can render text as it is produced
     * rather than waiting for the final message.
     *
     * [Raw] is the escape hatch. It exists so an unrecognised frame type is
     * *preserved* rather than dropped — if the desktop adds a frame kind, this app
     * keeps it visible for debugging instead of silently discarding it.
     */
    sealed interface FollowFrame {
        data class Snapshot(
            val sessionId: String?,
            val cwd: String?,
            val agentPreset: String?,
            val records: List<FollowEvent>,
        ) : FollowFrame

        data class Event(val event: FollowEvent) : FollowFrame
        data class AssistantStream(val frame: JsonObject) : FollowFrame
        data class Raw(val value: JsonElement?) : FollowFrame
    }

    /**
     * One decoded assistant token chunk.
     *
     * The live stream nests a `chunk` object one level down:
     * `{type:"chunk", attemptId, revision, index, chunk:{type:"text-delta", index, text}}`.
     * Only `text-delta` carries user-visible text; `block-start`, `usage`,
     * `block-end` and `finish` are bookkeeping and are ignored on purpose — they
     * are not content, and rendering them would corrupt the transcript.
     */
    data class Delta(val text: String, val blockIndex: Int)

    /**
     * List sessions.
     *
     * Note the `_request` argument name — this is the one endpoint in the
     * namespace that differs, and it is the easiest mistake to make when adding a
     * new call. The value is an empty object because this method takes no
     * parameters, but the key must still be present.
     *
     * ### `agentAvailable` vs `blank`
     *
     * The desktop's `session/list` result schema declares both
     * `agentAvailable: boolean` and `blank: boolean` on every item, and they mean
     * different things:
     *
     *  - `agentAvailable` — an agent is attached to this session, so it can accept
     *    a prompt. This is the field the UI needs, so it is read directly.
     *  - `blank` — a hint that the row is a freshly allocated, not-yet-used
     *    session. Upstream's own comment notes this hint can go stale and is
     *    reconciled against the `sessionListMetadata` projection.
     *
     * Read against the real schema (see `docs/sources.md`), deriving one from the
     * other would be inventing a relationship the Host does not claim. [list]
     * therefore takes `agentAvailable` as given and defaults to `true` when the
     * field is absent from an older Host — failing open on a display-only flag is
     * safer than greying out a session that would actually work.
     *
     * Returns only sessions the desktop reports; malformed rows are skipped rather
     * than failing the whole list, so one bad entry cannot blank the screen.
     */
    suspend fun list(): List<SessionSummary> {
        val value = client.call("session/list", buildJsonObject { put("_request", JsonObject(emptyMap())) })
        val items = value.asObjOrNull()?.arr("items") ?: return emptyList()
        return items.mapNotNull { el ->
            val o = el.asObjOrNull() ?: return@mapNotNull null
            val id = o.string("sessionId") ?: return@mapNotNull null
            // Title lives under projections.values, not at the top level. It is
            // absent until the desktop has generated one.
            val proj = o.obj("projections")?.obj("values")
            SessionSummary(
                id = id,
                title = proj?.string("title"),
                running = o.bool("running") ?: false,
                updatedAt = o.long("updatedAt") ?: 0L,
                cwd = o.string("cwd"),
                agentAvailable = o.bool("agentAvailable") ?: true,
            )
        }
    }

    /**
     * Create a session and return its id.
     *
     * @param cwd working directory; `null` lets the desktop pick its default.
     *   Only pass a path the user explicitly chose — this is a real directory on
     *   their machine.
     * @param agentPreset agent configuration name; `null` uses the desktop default.
     *
     * Session creation is a Host-side allocation, not a local draft. If this
     * throws, no session exists.
     */
    suspend fun create(cwd: String? = null, agentPreset: String? = null): String {
        val req = buildJsonObject {
            if (cwd != null) put("cwd", cwd)
            if (agentPreset != null) put("agentPreset", agentPreset)
        }
        val value = client.call("session/create", buildJsonObject { put("request", req) })
        return value.asObjOrNull()?.string("sessionId")
            ?: throw RemoteCallException(RemoteError("session/create", "Host returned no sessionId"))
    }

    /**
     * Submit a natural-language prompt.
     *
     * The Host answers `{accepted:true}` as soon as the prompt is in the inbox;
     * that is an acknowledgement, not a completion. Completion arrives later as
     * a `turn/end` event on the followed session. The UI must not conflate the
     * two — see `PocketViewModel.send`.
     *
     * @param mode [PromptMode.Queue] appends after the current turn;
     *   [PromptMode.Steer] redirects a running turn.
     * @param requestId caller-supplied idempotency key. The Host uses it to
     *   recognise a retried submission, so reusing an id is how a caller avoids
     *   starting the same task twice after a network blip.
     * @param clientTimeZone optional IANA zone so the desktop can render times in
     *   the user's local zone rather than the host's.
     */
    suspend fun prompt(
        sessionId: String,
        text: String,
        mode: PromptMode = PromptMode.Queue,
        requestId: String = UUID.randomUUID().toString(),
        clientTimeZone: String? = null,
    ): Boolean {
        val req = buildJsonObject {
            put("requestId", requestId)
            put("sessionId", sessionId)
            put("mode", mode.wire)
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    )
                }
            )
            if (clientTimeZone != null) put("clientTimeZone", clientTimeZone)
        }
        val value = client.call("session/prompt", buildJsonObject { put("request", req) })
        return value.asObjOrNull()?.bool("accepted") ?: false
    }

    /**
     * Request cancellation of the running turn.
     *
     * `{accepted:true}` means the *request* was received — not that the turn
     * stopped. Proof of the stop is a `turn/end` event on the followed stream,
     * which is why the ViewModel keeps the UI in a "requested" state until that
     * event arrives and downgrades to "unconfirmed" if it never does.
     */
    suspend fun cancel(sessionId: String): Boolean {
        val req = buildJsonObject { put("sessionId", sessionId) }
        val value = client.call("session/cancel", buildJsonObject { put("request", req) })
        return value.asObjOrNull()?.bool("accepted") ?: false
    }

    /**
     * Rename a session.
     *
     * Returns the title the desktop actually stored, which may differ from the
     * requested one (the desktop may normalise it). Callers should display the
     * returned value, not their input.
     */
    suspend fun rename(sessionId: String, title: String): String? {
        val req = buildJsonObject {
            put("sessionId", sessionId)
            put("title", title)
        }
        return client.call("session/rename", buildJsonObject { put("request", req) })
            .asObjOrNull()?.string("title")
    }

    /**
     * Follow one session's event journal plus the live assistant stream.
     *
     * The `address` object is what makes this endpoint reusable for subagents
     * later; this app only uses `{kind:"session", sessionId}`.
     *
     * `assistantStream: true` is what makes token deltas arrive at all. Without
     * it the stream only carries completed journal events, which would make the
     * UI feel frozen during a long answer.
     */
    suspend fun follow(sessionId: String): GatewayClient.StreamHandle {
        val req = buildJsonObject {
            put(
                "address",
                buildJsonObject {
                    put("kind", "session")
                    put("sessionId", sessionId)
                }
            )
            put("assistantStream", true)
        }
        return client.openStream("session/follow", buildJsonObject { put("request", req) })
    }

    /**
     * Decode one `session/follow` item into a [FollowFrame].
     *
     * Unknown frame types become [FollowFrame.Raw] instead of being dropped, so a
     * protocol addition on the desktop side stays observable.
     */
    fun decodeFrame(value: JsonElement?): FollowFrame {
        val o = value.asObjOrNull() ?: return FollowFrame.Raw(value)
        return when (o.string("type")) {
            "snapshot" -> {
                val header = o.obj("header")
                val records = o.arr("records")?.mapNotNull { r ->
                    val ro = r.asObjOrNull() ?: return@mapNotNull null
                    when (ro.string("type")) {
                        "event" -> ro.obj("event")?.let(::toEvent)
                        else -> null
                    }
                } ?: emptyList()
                FollowFrame.Snapshot(
                    sessionId = header?.string("id"),
                    cwd = header?.string("cwd"),
                    agentPreset = header?.string("agentPreset"),
                    records = records,
                )
            }
            "event" -> o.obj("event")?.let { FollowFrame.Event(toEvent(it)) } ?: FollowFrame.Raw(value)
            "assistant-stream" -> o.obj("frame")?.let { FollowFrame.AssistantStream(it) }
                ?: FollowFrame.Raw(value)
            else -> FollowFrame.Raw(value)
        }
    }

    /**
     * Project one raw event object onto the three envelope fields the app uses.
     *
     * `seq` and `time` default to `0` rather than failing: they are used for ordering
     * hints and display, never to decide whether something happened, so a Host that
     * omits them must not break event handling. `data` is kept as the raw
     * [JsonElement] and interpreted later by the specific decoders — see
     * [assistantText], which is the only place that knows what an
     * `assistant/message`'s body looks like.
     */
    private fun toEvent(o: JsonObject) = FollowEvent(
        type = o.string("type").orEmpty(),
        seq = o.long("seq") ?: 0L,
        time = o.long("time") ?: 0L,
        data = o["data"],
    )

    /**
     * Extract the assistant's visible text from an `assistant/message` event.
     *
     * The message content is a parts array (`[{type:"text", text:…}, …]`), not a
     * plain string, so it goes through [Json.contentText]. Non-text parts are
     * skipped rather than stringified.
     */
    fun assistantText(event: FollowEvent): String? {
        if (event.type != "assistant/message") return null
        val data = event.data.asObjOrNull() ?: return null
        val message = data.obj("message") ?: data
        return Json.contentText(message["content"]).ifBlank { null }
    }

    /**
     * Extract a user prompt's text, used to render the outgoing bubble.
     *
     * Callers must also check `data.source.kind == "user"`: the journal contains
     * `user/message` entries injected by the runtime itself (context, reminders),
     * and those are not things the person typed.
     */
    fun userText(event: FollowEvent): String? {
        if (event.type != "user/message") return null
        val data = event.data.asObjOrNull() ?: return null
        return Json.contentText(data["content"]).ifBlank { null }
    }

    /**
     * Text delta carried by an `assistant-stream` frame, when present.
     *
     * Returns `null` for every non-text frame (`start`, `block-start`, `usage`,
     * `block-end`, `end`, `finish`). That is the intended contract: the caller
     * appends whatever it gets and ignores `null`, so bookkeeping frames cost
     * nothing at the UI layer.
     */
    fun assistantDelta(frame: JsonObject): Delta? {
        if (frame.string("type") != "chunk") return null
        val chunk = frame.obj("chunk") ?: return null
        if (chunk.string("type") != "text-delta") return null
        val text = chunk.string("text") ?: return null
        return Delta(text = text, blockIndex = chunk.long("index")?.toInt() ?: 0)
    }

    /**
     * Extract the pending approval from a forwarded `$events` frame.
     *
     * Approvals arrive on the `$events` carrier, not on `session/follow`. The
     * Host pushes `{type:"waterfall", event:"approval/request", eventId,
     * agentId, request:{toolName, callId?, reason?}}` and expects the listener
     * to answer through `$events/result` with `{clientId, eventId, outcome}`.
     *
     * Why a waterfall and not a plain event: the desktop may have several
     * subscribers, and the first one to answer wins. This app answers
     * affirmatively only when the user taps "allow once"; otherwise it returns
     * `next` and lets the desktop's own policy decide. That is why a phone that
     * is merely *connected* never silently approves anything.
     *
     * Returns `null` for any frame that is not an approval request.
     */
    fun decodeApproval(value: JsonElement?): ApprovalRequest? {
        val o = value.asObjOrNull() ?: return null
        if (o.string("type") != "waterfall") return null
        if (o.string("event") != "approval/request") return null
        val eventId = o.string("eventId") ?: return null
        val req = o.obj("request")
        return ApprovalRequest(
            eventId = eventId,
            agentId = o.string("agentId"),
            toolName = req?.string("toolName").orEmpty(),
            callId = req?.string("callId"),
            reason = req?.string("reason"),
            raw = req,
        )
    }
}
