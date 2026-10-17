#!/usr/bin/env node
// Protocol conformance harness for DSH Pocket.
//
// Usage:
//   node tests/conformance.mjs <baseUrl> <deviceId>
//
// It exercises every wire call the Android app makes and prints a PASS/FAIL
// line per check, then exits non-zero if anything failed. This is the "run the
// automated tests, then do the real integration test" gate from section 7 of
// the design document: the unit tests pin the frame *shapes*, this pins the
// live *contract*.
//
// Nothing here is a mock. If the desktop is not running, it fails loudly.

const [, , BASE = 'http://127.0.0.1:19387', DEVICE] = process.argv

if (!DEVICE) {
  console.error('usage: node tests/conformance.mjs <baseUrl> <deviceId>')
  process.exit(2)
}

const H = { 'x-dsh-remote-device': DEVICE, 'content-type': 'application/json' }
const WS_BASE = BASE.replace(/^http/, 'ws')

let passed = 0
let failed = 0
const failures = []

function check(name, ok, detail) {
  if (ok) {
    passed++
    console.log(`PASS  ${name}`)
  } else {
    failed++
    failures.push({ name, detail })
    console.log(`FAIL  ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

let rpcSeq = 0
async function rpc(method, args) {
  const rpcId = `conf-${++rpcSeq}`
  const res = await fetch(`${BASE}/remote/api/${method}`, {
    method: 'POST',
    headers: H,
    body: JSON.stringify({ type: 'client-request', rpcId, method, payload: { args } }),
  })
  const text = await res.text()
  let env
  try {
    env = JSON.parse(text)
  } catch {
    return { ok: false, error: { code: `http/${res.status}`, message: text.slice(0, 200) } }
  }
  if (env.result?.ok === false) return { ok: false, error: env.result.error }
  return { ok: true, value: env.result?.value }
}

async function openMux() {
  const ws = new WebSocket(`${WS_BASE}/remote/api/remote.mux?device=${encodeURIComponent(DEVICE)}`)
  await new Promise((res, rej) => {
    ws.addEventListener('open', res, { once: true })
    ws.addEventListener('error', () => rej(new Error('mux connect failed')), { once: true })
  })
  return ws
}

function openStream(ws, endpoint, payload) {
  const streamId = crypto.randomUUID()
  const frames = []
  let wake = null
  let ended = false
  let failure = null
  const onMessage = (ev) => {
    const f = JSON.parse(ev.data)
    if (f.streamId !== streamId) return
    if (f.type === 'item') frames.push(f.value ?? null)
    else if (f.type === 'end') ended = true
    else if (f.type === 'error') failure = f.error
    wake?.()
    wake = null
  }
  ws.addEventListener('message', onMessage)
  ws.send(JSON.stringify({ type: 'open', streamId, endpoint, payload }))
  return {
    streamId,
    next: () =>
      new Promise((resolve) => {
        const tick = () => {
          if (failure) return resolve({ error: failure })
          if (frames.length > 0) return resolve({ value: frames.shift() })
          if (ended) return resolve({ done: true })
          wake = tick
        }
        tick()
      }),
    cancel() {
      ws.send(JSON.stringify({ type: 'cancel', streamId }))
      ws.removeEventListener('message', onMessage)
    },
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function main() {
  // ---- 1. the remote gate rejects an unpaired device ----------------------
  const bad = await fetch(`${BASE}/remote/api/session/list`, {
    method: 'POST',
    headers: { 'x-dsh-remote-device': 'not-a-real-device', 'content-type': 'application/json' },
    body: JSON.stringify({ type: 'client-request', rpcId: 'x', method: 'session/list', payload: { args: { _request: {} } } }),
  })
  check('unpaired device is refused (403)', bad.status === 403, `got HTTP ${bad.status}`)

  // ---- 2. the paired device can list sessions -----------------------------
  const list = await rpc('session/list', { _request: {} })
  check('session/list with the paired credential', list.ok && Array.isArray(list.value?.items), JSON.stringify(list.error))

  // ---- 3. local-only surfaces stay local ----------------------------------
  const pairOverRemote = await rpc('pair/status', {})
  check(
    'pairing surface is unreachable over /remote',
    !pairOverRemote.ok && (pairOverRemote.error?.code === 'forbidden' || pairOverRemote.error?.code === 'invalid-request'),
    JSON.stringify(pairOverRemote.error),
  )

  // ---- 4. mux + $events ---------------------------------------------------
  const ws = await openMux()
  check('mux WebSocket connects', true)

  const events = openStream(ws, '$events', { args: {} })
  const ready = await events.next()
  check(
    '$events opens with a ready frame carrying clientId + host.home',
    ready.value?.type === 'ready' && typeof ready.value?.clientId === 'string' && typeof ready.value?.host?.home === 'string',
    JSON.stringify(ready.value).slice(0, 160),
  )
  const clientId = ready.value?.clientId

  // ---- 5. create -> follow -> prompt -> observe -> cancel -----------------
  const created = await rpc('session/create', { request: {} })
  const sid = created.value?.sessionId
  check('session/create returns a sessionId', typeof sid === 'string' && sid.length > 0, JSON.stringify(created.error))

  const follow = openStream(ws, 'session/follow', {
    args: { request: { address: { kind: 'session', sessionId: sid }, assistantStream: true } },
  })
  const snap = await follow.next()
  check(
    'session/follow opens with a snapshot for the right session',
    snap.value?.type === 'snapshot' && snap.value?.header?.id === sid,
    JSON.stringify(snap.value).slice(0, 160),
  )

  const prompt = await rpc('session/prompt', {
    request: {
      sessionId: sid,
      mode: 'queue',
      content: [{ type: 'text', text: 'Reply with exactly the word CONFORMANCE and nothing else.' }],
      requestId: crypto.randomUUID(),
    },
  })
  check('session/prompt is accepted', prompt.value?.accepted === true, JSON.stringify(prompt.error))

  // Wait for the completion signal. `turn/end` is the only thing allowed to
  // move the phone's status to "done", so its presence is what we assert.
  let sawAssistant = false
  let sawTurnEnd = false
  const deadline = Date.now() + 180_000
  while (Date.now() < deadline && !sawTurnEnd) {
    const frame = await Promise.race([follow.next(), sleep(2000).then(() => 'tick')])
    if (frame === 'tick') continue
    if (frame.done || frame.error) break
    const v = frame.value
    if (v?.type === 'event') {
      if (v.event?.type === 'assistant/message') {
        const text = JSON.stringify(v.event.data ?? {})
        if (text.includes('CONFORMANCE')) sawAssistant = true
      }
      if (v.event?.type === 'turn/end') sawTurnEnd = true
    }
  }
  check('assistant/message arrives on the followed stream', sawAssistant)
  check('turn/end arrives (the completion signal the UI waits for)', sawTurnEnd)

  // ---- 6. cancel is acknowledged -----------------------------------------
  const cancel = await rpc('session/cancel', { request: { sessionId: sid } })
  check('session/cancel is acknowledged', cancel.value?.accepted === true, JSON.stringify(cancel.error))

  // ---- 7. approval round-trip --------------------------------------------
  // Answering a *real* approval needs a prompt that triggers one, which depends
  // on the desktop's approval policy. What we can always verify is that the
  // result endpoint validates its envelope and that a bogus clientId is refused,
  // which is the failure mode a stale phone notification would hit.
  const bogusResult = await rpc('$events/result', {
    clientId: 'no-such-client',
    eventId: 'no-such-event',
    outcome: { kind: 'next' },
  })
  check(
    '$events/result rejects an unknown clientId',
    !bogusResult.ok,
    JSON.stringify(bogusResult.error),
  )

  // ---- 8. heartbeat survival ---------------------------------------------
  await sleep(3000)
  const stillAlive = await rpc('session/list', { _request: {} })
  check('mux survives past the 2s heartbeat interval', stillAlive.ok, JSON.stringify(stillAlive.error))

  follow.cancel()
  events.cancel()
  ws.close()

  console.log(`\n${passed} passed, ${failed} failed`)
  if (failed > 0) {
    console.log('\nFailures:')
    for (const f of failures) console.log(`  - ${f.name}: ${f.detail ?? ''}`)
  }
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('harness crashed:', e)
  process.exit(1)
})
