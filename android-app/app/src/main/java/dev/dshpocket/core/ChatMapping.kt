package dev.dshpocket.core

import dev.dshpocket.protocol.FollowEvent
import dev.dshpocket.protocol.Json
import dev.dshpocket.protocol.arr
import dev.dshpocket.protocol.asObjOrNull
import dev.dshpocket.protocol.bool
import dev.dshpocket.protocol.obj
import dev.dshpocket.protocol.string

/**
 * Replay a session journal into the entries a returning user should see.
 *
 * ### Why this is a separate function from the live handler
 *
 * A session can be opened long after it started, and `session/follow` then begins
 * with a `snapshot` containing the whole journal. The live path in
 * `PocketViewModel.handleEvent` and this replay path must produce *the same*
 * entries for the same events, otherwise opening a session twice would show two
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
 * means a re-delivered event updates an existing row instead of appending a
 * duplicate — which happens on every reconnect, because the follow stream
 * replays from a cursor.
 *
 * ### Tool pairing
 *
 * The Host journals a tool run as **two** events, `tool/call` (carrying
 * `{callId, name, arguments}`) and `tool/result` (carrying
 * `{message:{toolCallId, content, isError}, error?}`). This function emits them
 * as two entries tagged with the same [ChatItem.toolCallId]; the caller then
 * merges them with [pairToolEntries]. Doing the merge here would need mutable
 * state across the map, and doing it in the UI would duplicate the logic between
 * the replay and live paths.
 *
 * Tool output is truncated to 2000 characters. That is a display decision, not a
 * data one: the full text is still on the desktop, and a phone that tried to
 * render a 10 MB build log would simply hang. The UI additionally collapses long
 * output behind a tap, so 2000 characters never all appear at once.
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
        val data = event.data.asObjOrNull()
        val name = data?.string("name")
        if (name.isNullOrBlank()) null
        else ChatItem(
            key = "t-${event.seq}",
            role = ChatItem.Role.Tool,
            // No result yet: the body stays empty and the UI shows a running
            // state instead of pretending the tool produced nothing.
            text = "",
            detail = summarizeToolArguments(data.string("arguments")),
            toolName = name,
            toolRunning = true,
            toolCallId = data.string("callId"),
        )
    }

    "tool/result" -> {
        val data = event.data.asObjOrNull()
        val msg = data?.obj("message")
        val text = Json.contentText(msg?.arr("content"))
        val callId = msg?.string("toolCallId")
            ?: msg?.obj("source")?.string("callId")
            ?: data?.string("callId")
        // A failed call may carry no content at all, only `error`. Surfacing the
        // error text is the whole point here: a tool that broke silently is worse
        // than one that broke loudly.
        val errorText = data?.obj("error")?.let { err ->
            listOfNotNull(err.string("name"), err.string("reason") ?: err.string("code"))
                .joinToString(" · ")
                .takeIf { it.isNotBlank() }
        }
        val body = text.ifBlank { errorText ?: "" }
        val failed = msg?.bool("isError") == true || data?.obj("error") != null
        if (body.isBlank() && callId == null) null
        else ChatItem(
            key = "tr-${event.seq}",
            role = ChatItem.Role.Tool,
            text = body.take(2000),
            isError = failed,
            toolRunning = false,
            toolCallId = callId,
        )
    }

    else -> null
}

/**
 * Merge each `tool/result` entry into the `tool/call` entry that shares its
 * [ChatItem.toolCallId], so one tool run renders as one row.
 *
 * Order matters and is preserved: the merged row keeps the *call's* position in
 * the list, because that is where the run started. A result whose call is not in
 * the list — which happens when a session is opened mid-turn and the snapshot
 * cursor lands after the call — is kept as its own row rather than dropped;
 * losing output is worse than showing it detached.
 *
 * A call with no result keeps [ChatItem.toolRunning] true, which is how the UI
 * knows to show a spinner. That is also the honest state for a turn that was
 * cancelled between the two events.
 */
internal fun pairToolEntries(items: List<ChatItem>): List<ChatItem> {
    if (items.none { it.role == ChatItem.Role.Tool }) return items

    // callId -> index of the tool/call row awaiting a result.
    val pending = LinkedHashMap<String, Int>()
    items.forEachIndexed { index, item ->
        if (item.role == ChatItem.Role.Tool && item.toolRunning) {
            item.toolCallId?.let { pending[it] = index }
        }
    }
    if (pending.isEmpty()) return items

    val merged = items.toMutableList()
    // Rows consumed as a result get removed at the end, so the indices collected
    // above stay valid while we iterate.
    val consumed = HashSet<Int>()
    items.forEachIndexed { index, item ->
        if (item.role != ChatItem.Role.Tool || item.toolRunning) return@forEachIndexed
        val callId = item.toolCallId ?: return@forEachIndexed
        val callIndex = pending.remove(callId) ?: return@forEachIndexed
        val call = merged[callIndex]
        merged[callIndex] = call.copy(
            text = item.text,
            isError = item.isError,
            toolRunning = false,
        )
        consumed += index
    }
    if (consumed.isEmpty()) return items
    return merged.filterIndexed { index, _ -> index !in consumed }
}

/**
 * Turn a tool's raw JSON `arguments` string into one scannable line.
 *
 * ### Why this exists
 *
 * The complaint that started this rework was that the transcript was unreadable:
 * `工具 · edit` told you a tool ran but not *on what*. A path or a command is the
 * single most informative field, and it is always present in one of a handful of
 * shapes. Anything unrecognised falls back to null rather than dumping raw JSON,
 * because a wall of escaped JSON is exactly the unreadable thing we are fixing.
 *
 * The returned line is already clipped: it is a header, not content.
 */
internal fun summarizeToolArguments(rawArguments: String?): String? {
    val args = rawArguments?.trim().orEmpty()
    if (args.isEmpty()) return null

    // Cheap targeted extraction instead of a JSON parse: `arguments` is a string
    // field whose payload is a JSON object, and it can be large or (while
    // streaming) truncated. Looking for the few keys we render cannot fail.
    for (key in ARGUMENT_KEYS) {
        val value = extractStringField(args, key) ?: continue
        val cleaned = collapseWhitespace(value)
        if (cleaned.isNotBlank()) return cleaned.take(120)
    }
    return null
}

/**
 * Keys worth showing in a tool header, most specific first.
 *
 * `command` before `path` because for a shell tool the command explains the run
 * while the working directory does not; for a file tool `file_path`/`path` is
 * the only interesting thing.
 */
private val ARGUMENT_KEYS = listOf(
    "command",
    "file_path",
    "path",
    "pattern",
    "url",
    "query",
    "description",
)

/**
 * Pull one string value out of a JSON-ish object without parsing it.
 *
 * Deliberately tolerant: the payload may be truncated mid-object while the model
 * is still streaming arguments, and a parse failure there would erase the header
 * for a run that is perfectly fine. Returns the raw inner text with escapes
 * unescaped for the handful of sequences that actually appear in paths and
 * commands.
 */
private fun extractStringField(json: String, key: String): String? {
    val needle = "\"$key\""
    var from = 0
    while (true) {
        val at = json.indexOf(needle, from)
        if (at < 0) return null
        from = at + needle.length
        // Skip whitespace, expect a colon, skip whitespace, expect a quote.
        var i = from
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != ':') continue
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') continue
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                when (val esc = json[i + 1]) {
                    'n', 't', 'r' -> sb.append(' ')
                    '"', '\\', '/' -> sb.append(esc)
                    'u' -> {
                        // \uXXXX — the only reason this matters is Windows paths
                        // and CJK text, both of which appear here.
                        val hex = json.substring(i + 2, minOf(i + 6, json.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null) sb.append(code.toChar())
                        i += 6
                        continue
                    }
                    else -> sb.append(esc)
                }
                i += 2
                continue
            }
            if (c == '"') return sb.toString()
            sb.append(c)
            i++
        }
        // Unterminated string: streaming arguments. Use what we have.
        return sb.toString().ifBlank { null }
    }
}

/** Newlines and runs of spaces collapse to single spaces, for a one-line header. */
private fun collapseWhitespace(value: String): String =
    value.replace(Regex("\\s+"), " ").trim()
