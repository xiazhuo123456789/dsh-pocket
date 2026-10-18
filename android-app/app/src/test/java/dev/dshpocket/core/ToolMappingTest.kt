package dev.dshpocket.core

import dev.dshpocket.protocol.FollowEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool events are the part of the transcript users found unreadable, so the
 * pairing rules get their own tests.
 *
 * ### What these lock down
 *
 *  1. A `tool/call` renders as a **running** row carrying the argument summary,
 *     not as a bare tool name. Without the summary the transcript says a tool ran
 *     but never on what.
 *  2. A `tool/result` merges into that row rather than appending a second one.
 *     This is the "乱七八糟" bug: two grey boxes per tool run, with nothing tying
 *     the output to the call.
 *  3. An orphan result (snapshot cursor landed after its call) is kept, not
 *     dropped — losing output is worse than showing it detached.
 *  4. A call whose result never arrived stays `toolRunning`, which is the honest
 *     state for a turn cancelled midway.
 *
 * The fixtures are hand-trimmed copies of frames observed from a live desktop;
 * every field name and shape the decoder reads is preserved verbatim. See
 * `docs/sources.md`.
 */
class ToolMappingTest {

    private fun event(type: String, seq: Long, data: String): FollowEvent =
        FollowEvent(
            type = type,
            seq = seq,
            time = 0L,
            data = Json.parseToJsonElement(data),
        )

    // ------------------------------------------------------------------
    // summarizeToolArguments
    // ------------------------------------------------------------------

    @Test
    fun `a shell command is summarised from its arguments`() {
        val args = """{"command":"npm run build","timeout":120000}"""
        assertEquals("npm run build", summarizeToolArguments(args))
    }

    @Test
    fun `a file edit is summarised by its path`() {
        val args = """{"file_path":"E:\\work\\main.kt","old_string":"a","new_string":"b"}"""
        assertEquals("E:\\work\\main.kt", summarizeToolArguments(args))
    }

    @Test
    fun `command wins over path when both are present`() {
        // A shell tool may carry both; the command is what explains the run.
        val args = """{"path":"/tmp","command":"ls -la"}"""
        assertEquals("ls -la", summarizeToolArguments(args))
    }

    @Test
    fun `escaped sequences are unescaped for display`() {
        val args = """{"command":"echo \"hi\"\n\techo done"}"""
        assertEquals("""echo "hi" echo done""", summarizeToolArguments(args))
    }

    @Test
    fun `truncated streaming arguments still yield a summary`() {
        // While the model is still streaming, the closing quote is absent. The
        // header must not disappear for a run that is perfectly fine.
        val args = """{"command":"cargo build --relea"""
        assertEquals("cargo build --relea", summarizeToolArguments(args))
    }

    @Test
    fun `unrecognised arguments produce no detail rather than raw json`() {
        // A wall of escaped JSON is exactly the unreadable thing being fixed.
        assertNull(summarizeToolArguments("""{"unrelated":123}"""))
        assertNull(summarizeToolArguments(""))
        assertNull(summarizeToolArguments(null))
    }

    @Test
    fun `a long summary is clipped`() {
        val long = "x".repeat(400)
        val out = summarizeToolArguments("""{"command":"$long"}""")
        assertEquals(120, out?.length)
    }

    // ------------------------------------------------------------------
    // pairing
    // ------------------------------------------------------------------

    @Test
    fun `a tool call starts out running with an empty body`() {
        val item = toChatItem(
            event(
                "tool/call",
                5,
                """{"turn":1,"step":1,"callId":"c1","name":"edit","arguments":"{\"file_path\":\"/a.kt\"}"}""",
            )
        )!!
        assertEquals(ChatItem.Role.Tool, item.role)
        assertEquals("edit", item.toolName)
        assertEquals("/a.kt", item.detail)
        assertEquals("c1", item.toolCallId)
        assertTrue(item.toolRunning)
        assertEquals("", item.text)
    }

    @Test
    fun `a tool result merges into its call and keeps the call's position`() {
        val callItem = toChatItem(
            event(
                "tool/call",
                5,
                """{"turn":1,"step":1,"callId":"c1","name":"edit","arguments":"{\"file_path\":\"/a.kt\"}"}""",
            )
        )!!
        val other = ChatItem(key = "a-6", role = ChatItem.Role.Assistant, text = "done")
        val resultItem = toChatItem(
            event(
                "tool/result",
                7,
                """{"turn":1,"step":1,"message":{"toolCallId":"c1","isError":false,"content":[{"type":"text","text":"updated"}]}}""",
            )
        )!!

        val merged = pairToolEntries(listOf(callItem, other, resultItem))
        assertEquals(2, merged.size)
        // The run keeps the position where it started, not where it finished.
        assertEquals("t-5", merged[0].key)
        assertEquals("updated", merged[0].text)
        assertEquals("edit", merged[0].toolName)
        assertTrue(!merged[0].toolRunning)
        assertEquals("a-6", merged[1].key)
    }

    @Test
    fun `two tools in one turn each merge with their own result`() {
        val a = toChatItem(event("tool/call", 1, """{"callId":"a","name":"read","arguments":"{}"}"""))!!
        val b = toChatItem(event("tool/call", 2, """{"callId":"b","name":"edit","arguments":"{}"}"""))!!
        // Results arrive out of order relative to the calls -- this is normal when
        // tools run concurrently.
        val rb = toChatItem(
            event("tool/result", 3, """{"message":{"toolCallId":"b","content":[{"type":"text","text":"B"}]}}""")
        )!!
        val ra = toChatItem(
            event("tool/result", 4, """{"message":{"toolCallId":"a","content":[{"type":"text","text":"A"}]}}""")
        )!!

        val merged = pairToolEntries(listOf(a, b, rb, ra))
        assertEquals(2, merged.size)
        assertEquals("a", merged[0].toolCallId)
        assertEquals("A", merged[0].text)
        assertEquals("b", merged[1].toolCallId)
        assertEquals("B", merged[1].text)
        assertTrue(merged.none { it.toolRunning })
    }

    @Test
    fun `an orphan result is kept rather than dropped`() {
        val resultItem = toChatItem(
            event("tool/result", 9, """{"message":{"toolCallId":"nope","content":[{"type":"text","text":"late"}]}}""")
        )!!
        val merged = pairToolEntries(listOf(resultItem))
        assertEquals(1, merged.size)
        assertEquals("late", merged[0].text)
    }

    @Test
    fun `a call with no result stays running`() {
        val callItem = toChatItem(event("tool/call", 1, """{"callId":"x","name":"pwsh","arguments":"{}"}"""))!!
        val merged = pairToolEntries(listOf(callItem))
        assertTrue(merged.single().toolRunning)
    }

    @Test
    fun `a failed result carries the error flag and the error text`() {
        val item = toChatItem(
            event(
                "tool/result",
                3,
                """{"message":{"toolCallId":"c","isError":true,"content":[]},"error":{"name":"ToolError","code":"E_FAIL","reason":"file not found"}}""",
            )
        )!!
        assertTrue(item.isError)
        // Content was empty, so the reason has to come from `error` -- otherwise a
        // broken tool would render as an empty block with no explanation.
        assertEquals("ToolError · file not found", item.text)
    }

    @Test
    fun `an empty successful result with no content is dropped`() {
        val item = toChatItem(
            event("tool/result", 3, """{"message":{"toolCallId":"c","content":[]}}""")
        )
        // Nothing to say and a callId to say it to: the caller still needs the
        // row updated from running to done, so this is not null.
        assertEquals("", item?.text)
    }
}
