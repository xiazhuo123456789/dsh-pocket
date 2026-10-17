package dev.dshpocket.core

import dev.dshpocket.protocol.FollowEvent
import dev.dshpocket.protocol.Json
import dev.dshpocket.protocol.arr
import dev.dshpocket.protocol.asObjOrNull
import dev.dshpocket.protocol.bool
import dev.dshpocket.protocol.obj
import dev.dshpocket.protocol.string

/**
 * Replay a session journal into the bubbles a returning user should see.
 *
 * Only events that produced something visible to a human become bubbles; the
 * request/context/header bookkeeping is dropped, and an assistant turn whose
 * text was already streamed is not duplicated because the journal carries one
 * `assistant/message` per completed block.
 */
internal fun toChatItem(event: FollowEvent): ChatItem? = when (event.type) {
    "user/message" -> {
        val source = event.data.asObjOrNull()?.obj("source")?.string("kind")
        val text = Json.contentText(event.data.asObjOrNull()?.get("content"))
        if (source == "user" && text.isNotBlank()) {
            ChatItem(key = "u-${event.seq}", role = ChatItem.Role.User, text = text)
        } else null
    }

    "assistant/message" -> {
        val data = event.data.asObjOrNull()
        val message = data?.obj("message") ?: data
        val text = Json.contentText(message?.get("content"))
        if (text.isNotBlank()) {
            ChatItem(key = "a-${event.seq}", role = ChatItem.Role.Assistant, text = text)
        } else null
    }

    "tool/call" -> {
        val name = event.data.asObjOrNull()?.string("name")
        if (name.isNullOrBlank()) null
        else ChatItem(key = "t-${event.seq}", role = ChatItem.Role.Tool, text = name, toolName = name)
    }

    "tool/result" -> {
        val msg = event.data.asObjOrNull()?.obj("message")
        val text = Json.contentText(msg?.arr("content"))
        if (text.isBlank()) null
        else ChatItem(
            key = "tr-${event.seq}",
            role = ChatItem.Role.Tool,
            text = text.take(2000),
            isError = msg?.bool("isError") == true,
        )
    }

    else -> null
}
