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
 * ### Why this is a separate function from the live handler
 *
 * A session can be opened long after it started, and `session/follow` then begins
 * with a `snapshot` containing the whole journal. The live path in
 * `PocketViewModel.handleEvent` and this replay path must produce *the same*
 * bubbles for the same events, otherwise opening a session twice would show two
 * different transcripts. Keeping the mapping in one pure function is what makes
 * that testable without a Host (see `ProtocolTest`).
 *
 * ### What is deliberately dropped
 *
 * `request/header`, `request/context`, `agent/inbox/spliced`, `step/start`,
 * `step/end` and `system/message` are bookkeeping. They are real events, but they
 * are not things a person said or received, and rendering them would bury the
 * conversation in noise.
 *
 * ### Why `user/message` is filtered by `source.kind`
 *
 * The journal contains `user/message` entries that the *runtime* injected —
 * reminders, context, tool scaffolding. Those are not things the user typed.
 * Only entries whose `source.kind` is `"user"` become bubbles. Without this
 * filter the transcript would show the phone owner saying things they never said,
 * which in a control app is a serious misrepresentation.
 *
 * ### Why the directive strip must match the live path exactly
 *
 * `send` appends a reply-language directive as its own content part, so journaled
 * user turns contain two text parts. Both this replay path and the live handler
 * in `PocketViewModel` strip it with the same `PromptDirectives` helper. If they
 * disagreed, the same turn would render differently depending on whether the user
 * opened the session live or after a reload — the exact class of bug the "one
 * mapping function" rule exists to prevent. See [PromptDirectives].
 *
 * ### Why the keys are seq-based
 *
 * `seq` is the desktop's monotonic event sequence. Using it as the Compose key
 * means a re-delivered event updates an existing bubble instead of appending a
 * duplicate — which happens on every reconnect, because the follow stream
 * replays from a cursor.
 *
 * Tool output is truncated to 2000 characters. That is a display decision, not a
 * data one: the full text is still on the desktop, and a phone that tried to
 * render a 10 MB build log would simply hang.
 */
internal fun toChatItem(event: FollowEvent): ChatItem? = when (event.type) {
    "user/message" -> {
        val source = event.data.asObjOrNull()?.obj("source")?.string("kind")
        val text = PromptDirectives.contentTextWithoutDirectives(event.data.asObjOrNull()?.get("content"))
        if (source == "user" && text.isNotBlank()) {
            ChatItem(key = "u-${event.seq}", role = ChatItem.Role.User, text = text)
        } else null
    }

    "assistant/message" -> {
        val data = event.data.asObjOrNull()
        // The message can be nested under `message` or be the data itself,
        // depending on the event variant; both occur in observed traffic.
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
            // A failed tool result is still content the user needs to see; the
            // flag only changes its styling, never hides it.
            isError = msg?.bool("isError") == true,
        )
    }

    else -> null
}
