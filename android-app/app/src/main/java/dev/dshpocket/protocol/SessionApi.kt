package dev.dshpocket.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Typed facade over the `session` Remote namespace of
 * `@deepseek-ai/dsh-api-session-controller`.
 *
 * Wire-argument names are read from the Host's generated descriptor and are
 * **not** uniform: `session/list` takes `_request`, while `session/create`,
 * `session/prompt`, `session/cancel` and `session/follow` take `request`.
 * Mixing them up yields `gateway/arguments-invalid`, so they are pinned here.
 */
class SessionApi(private val client: GatewayClient) {

    data class SessionSummary(
        val sessionId: String,
        val title: String?,
        val running: Boolean,
        val blank: Boolean,
        val cwd: String?,
        val updatedAt: Long?,
    )

    /** One decoded `session/follow` frame. */
    sealed interface FollowFrame {
        data class Snapshot(
            val sessionId: String?,
            val cwd: String?,
            val agentPreset: String?,
            val records: List<FollowEvent>,
        ) : FollowFrame

        data class Event(val event: FollowEvent) : FollowFrame
        data class AssistantStream(val frame: JsonObject) : FollowFrame
        data class Raw(val value: kotlinx.serialization.json.JsonElement?) : FollowFrame
    }

    /**
     * One decoded assistant token chunk.
     *
     * The live stream nests a `chunk` object one level down:
     * `{type:"chunk", attemptId, revision, index, chunk:{type:"text-delta", index, text}}`.
     * Only `text-delta` carries user-visible text; `block-start`, `usage`,
     * `block-end` and `finish` are bookkeeping.
     */
    data class Delta(val text: String, val blockIndex: Int)

    suspend fun list(): List<SessionSummary> {
        val value = client.call("session/list", buildJsonObject { put("_request", JsonObject(emptyMap())) })
        val items = value.asObjOrNull()?.arr("items") ?: return emptyList()
        return items.mapNotNull { el ->
            val o = el.asObjOrNull() ?: return@mapNotNull null
            val id = o.string("sessionId") ?: return@mapNotNull null
            val proj = o.obj("projections")?.obj("values")
            SessionSummary(
                sessionId = id,
                title = proj?.string("title"),
                running = o.bool("running") ?: false,
                blank = o.bool("blank") ?: false,
                cwd = o.string("cwd"),
                updatedAt = o.long("updatedAt"),
            )
        }
    }

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
     * a `turn/end` event on the followed session.
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

    suspend fun cancel(sessionId: String): Boolean {
        val req = buildJsonObject { put("sessionId", sessionId) }
        val value = client.call("session/cancel", buildJsonObject { put("request", req) })
        return value.asObjOrNull()?.bool("accepted") ?: false
    }

    suspend fun rename(sessionId: String, title: String): String? {
        val req = buildJsonObject {
            put("sessionId", sessionId)
            put("title", title)
        }
        return client.call("session/rename", buildJsonObject { put("request", req) })
            .asObjOrNull()?.string("title")
    }

    /** Follow one session's event journal plus the live assistant stream. */
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

    /** Decode one `session/follow` item into a [FollowFrame]. */
    fun decodeFrame(value: kotlinx.serialization.json.JsonElement?): FollowFrame {
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

    private fun toEvent(o: JsonObject) = FollowEvent(
        type = o.string("type").orEmpty(),
        seq = o.long("seq") ?: 0L,
        time = o.long("time") ?: 0L,
        data = o["data"],
    )

    /** Extract the assistant's visible text from an `assistant/message` event. */
    fun assistantText(event: FollowEvent): String? {
        if (event.type != "assistant/message") return null
        val data = event.data.asObjOrNull() ?: return null
        val message = data.obj("message") ?: data
        return Json.contentText(message["content"]).ifBlank { null }
    }

    /** Extract a user prompt's text, used to render the outgoing bubble. */
    fun userText(event: FollowEvent): String? {
        if (event.type != "user/message") return null
        val data = event.data.asObjOrNull() ?: return null
        return Json.contentText(data["content"]).ifBlank { null }
    }

    /** Text delta carried by an `assistant-stream` frame, when present. */
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
     */
    fun decodeApproval(value: kotlinx.serialization.json.JsonElement?): ApprovalRequest? {
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
