package dev.dshpocket.protocol

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol decoding tests.
 *
 * The fixtures are trimmed copies of real frames captured from a live Host (see
 * `tools/dump-events.mjs` in the repo root). They are asserted here because the
 * wire format is the one thing the phone cannot negotiate: if the Host changes a
 * field name, the app must fail loudly in CI rather than quietly show nothing.
 */
class ProtocolTest {

    private fun obj(json: String) = Json.lenient.parseToJsonElement(json).jsonObject

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

    @Test
    fun `non approval frames are ignored`() {
        val api = SessionApi(GatewayClient.stub())
        assertNull(api.decodeApproval(obj("""{"type":"emit","event":"api-session/status"}""")))
        assertNull(api.decodeApproval(obj("""{"type":"ready","clientId":"c"}""")))
        assertNull(api.decodeApproval(null))
    }

    @Test
    fun `invite link parses base url and token`() {
        val invite = PairingApi().parseInvite(
            "https://example-words-here.trycloudflare.com/pair-accept?pair=dummy-token-1"
        )!!
        assertEquals("https://example-words-here.trycloudflare.com", invite.baseUrl)
        assertEquals("dummy-token-1", invite.token)
    }

    @Test
    fun `invite link tolerates trailing path and query noise`() {
        val invite = PairingApi().parseInvite(
            "  https://1.2.3.4:8443/pair-accept?pair=abc&next=1  "
        )!!
        assertEquals("https://1.2.3.4:8443", invite.baseUrl)
        assertEquals("abc", invite.token)
    }

    @Test
    fun `invite rejects non pairing urls`() {
        val api = PairingApi()
        assertNull(api.parseInvite(""))
        assertNull(api.parseInvite("not a url"))
        assertNull(api.parseInvite("https://example.com/"))
    }
}
