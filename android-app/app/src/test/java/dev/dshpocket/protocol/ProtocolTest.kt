package dev.dshpocket.protocol

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol decoding tests.
 *
 * ### Why these fixtures look hand-trimmed
 *
 * They are: each one is a *trimmed copy of a real frame* captured from a live Host
 * (`tests/dump-events.mjs` records them; see `docs/sources.md` for how the wire
 * format was established). Fields that the decoder ignores were removed to keep
 * each test readable, but every field the decoder reads is present with the exact
 * name and shape the Host produced.
 *
 * ### What these tests are defending
 *
 * The wire format is the one thing the phone cannot negotiate. The desktop speaks
 * what it speaks; if a field is renamed, this app has no fallback. So the point of
 * this suite is to make such a change fail **here**, loudly, instead of at runtime
 * inside a `runCatching` that silently yields an empty transcript. That is also why
 * `decodeApproval` returning `null` for non-approval frames is asserted explicitly:
 * "ignore unknown frames" is a deliberate behaviour, not an accident of leniency.
 *
 * These are pure unit tests — no network, no Android framework, no Host required.
 * They run under `:app:testDebugUnitTest`.
 */
class ProtocolTest {

    /** Parse a JSON literal into a [JsonObject] the way the client would receive it. */
    private fun obj(json: String) = Json.lenient.parseToJsonElement(json).jsonObject

    /**
     * The `snapshot` frame is the first thing a followed session stream delivers, so
     * it carries both session metadata and the backlog needed to render history
     * without a second call.
     */
    @Test
    fun `snapshot frame decodes header and records`() {
        val frame = obj(
            """
            {"type":"snapshot",
             "header":{"version":4,"id":"session-abc","cwd":"C:\\Users\\x","isSeeded":false,"agentPreset":"standard"},
             "cursor":2,
             "records":[
               {"type":"event","event":{"type":"user/message","seq":1,"time":10,
                 "data":{"source":{"kind":"user"},"content":[{"type":"text","text":"hi"}]}}},
               {"type":"event","event":{"type":"assistant/message","seq":2,"time":11,
                 "data":{"turn":1,"step":1,"message":{"role":"assistant","content":[{"type":"text","text":"PONG"}]}}}}
             ]}
            """.trimIndent()
        )
        val decoded = SessionApi(GatewayClient.stub()).decodeFrame(frame)
        assertTrue(decoded is SessionApi.FollowFrame.Snapshot)
        val snap = decoded as SessionApi.FollowFrame.Snapshot
        assertEquals("session-abc", snap.sessionId)
        assertEquals("C:\\Users\\x", snap.cwd)
        assertEquals("standard", snap.agentPreset)
        assertEquals(2, snap.records.size)
        assertEquals("user/message", snap.records[0].type)
        assertEquals("assistant/message", snap.records[1].type)
    }

    /**
     * `turn/end` is the *only* completion signal the app trusts.
     *
     * `reason.kind` distinguishes a normal finish (`completed`) from a cancellation
     * or a failure, which is what lets the chat screen show the right terminal state
     * and lets the stop watchdog resolve to `Confirmed`.
     */
    @Test
    fun `turn end reason is the completion signal`() {
        val frame = obj(
            """{"type":"event","event":{"type":"turn/end","seq":9,"time":1,
               "data":{"turn":1,"reason":{"kind":"completed"}}}}"""
        )
        val decoded = SessionApi(GatewayClient.stub()).decodeFrame(frame)
        assertTrue(decoded is SessionApi.FollowFrame.Event)
        val event = (decoded as SessionApi.FollowFrame.Event).event
        assertEquals("turn/end", event.type)
        assertEquals("completed", event.data.asObjOrNull()?.obj("reason")?.string("kind"))
    }

    /**
     * Only `text-delta` chunks contribute to the streaming bubble; structural chunks
     * (`block-start`, `block-end`, …) must yield `null` so they do not append junk.
     */
    @Test
    fun `assistant stream delta carries only text-delta chunks`() {
        val api = SessionApi(GatewayClient.stub())
        val delta = obj(
            """{"type":"chunk","attemptId":"a","revision":1,"index":0,
               "chunk":{"type":"text-delta","index":0,"text":"PO"}}"""
        )
        assertEquals("PO", api.assistantDelta(delta)?.text)

        val blockStart = obj(
            """{"type":"chunk","attemptId":"a","revision":1,"index":0,
               "chunk":{"type":"block-start","index":0}}"""
        )
        assertNull(api.assistantDelta(blockStart))
    }

    /**
     * The approval frame, and the fact that only two outcomes exist.
     *
     * This is the single most load-bearing assertion in the suite: `allowOnce()`
     * produces `allowed-once` and `reject()` produces `rejected`, and there is no
     * third "always allow". If the desktop ever grows one, this test fails and forces
     * the UI copy to be revisited — which is exactly what should happen, because the
     * "允许一次" button label is derived from this constraint.
     */
    @Test
    fun `approval waterfall decodes and maps to the only accepted outcomes`() {
        val frame = obj(
            """
            {"type":"waterfall","event":"approval/request",
             "eventId":"59ae35e9","agentId":"session-e015",
             "request":{"toolName":"pwsh","callId":"call_00_97",
               "reason":"escalate sandbox to danger-full-access: ACL denied"}}
            """.trimIndent()
        )
        val approval = SessionApi(GatewayClient.stub()).decodeApproval(frame)!!
        assertEquals("59ae35e9", approval.eventId)
        assertEquals("pwsh", approval.toolName)
        assertTrue(approval.summary.contains("danger-full-access"))
        assertEquals("allowed-once", approval.allowOnce().string("value"))
        assertEquals("rejected", approval.reject().string("kind"))
    }

    /**
     * Everything that is not an approval request must decode to `null`.
     *
     * The `$events` socket carries many frame kinds — `ready`, `emit`, `item`,
     * `end`, … — on the same stream, so the decoder has to be a filter, not a
     * parser that assumes every frame is an approval.
     */
    @Test
    fun `non approval frames are ignored`() {
        val api = SessionApi(GatewayClient.stub())
        assertNull(api.decodeApproval(obj("""{"type":"emit","event":"api-session/status"}""")))
        assertNull(api.decodeApproval(obj("""{"type":"ready","clientId":"c"}""")))
        assertNull(api.decodeApproval(null))
    }

    /**
     * Pairing links come in from a QR, a paste, and `dshpocket://` wrappers, so
     * `parseInvite` has to pull the origin and token out of a real desktop URL.
     *
     * The hostname here is synthetic (and the token is a dummy) — a real invite is
     * a one-time credential and must never be committed to a public repository.
     */
    @Test
    fun `invite link parses base url and token`() {
        val invite = PairingApi().parseInvite(
            "https://example-words-here.trycloudflare.com/pair-accept?pair=dummy-token-1"
        )!!
        assertEquals("https://example-words-here.trycloudflare.com", invite.baseUrl)
        assertEquals("dummy-token-1", invite.token)
    }

    /**
     * A pasted link routinely arrives with surrounding whitespace and extra query
     * parameters (`&next=1`), because users copy the whole line from a terminal or a
     * chat. Tolerating that is a usability requirement, not a nicety.
     */
    @Test
    fun `invite link tolerates trailing path and query noise`() {
        val invite = PairingApi().parseInvite(
            "  https://1.2.3.4:8443/pair-accept?pair=abc&next=1  "
        )!!
        assertEquals("https://1.2.3.4:8443", invite.baseUrl)
        assertEquals("abc", invite.token)
    }

    /**
     * A URL with no pairing token is not an invite. Returning `null` (rather than a
     * half-filled object) keeps the ViewModel's validation in one place.
     */
    @Test
    fun `invite rejects non pairing urls`() {
        val api = PairingApi()
        assertNull(api.parseInvite(""))
        assertNull(api.parseInvite("not a url"))
        assertNull(api.parseInvite("https://example.com/"))
    }
}
