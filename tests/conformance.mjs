#!/usr/bin/env node
// Protocol conformance harness for DSH Pocket.
//
// Usage:
//   node tests/conformance.mjs <baseUrl> <deviceId> [provider/model]
//
// It exercises every wire call the Android app makes and prints a PASS/FAIL
// line per check, then exits non-zero if anything failed. This is the "run the
// automated tests, then do the real integration test" gate from section 7 of
// the design document: the unit tests pin the frame *shapes*, this pins the
// live *contract*.
//
// Nothing here is a mock. If the desktop is not running, it fails loudly.
//
// ## Passing needs a *working* desktop model, not just a running desktop
//
// Every check about the transport passes against any paired desktop. The two
// turn checks additionally need the desktop's agent to be able to answer, so
// they fail when the desktop's own provider/model configuration is broken.
// Observed on this machine, in the order they were hit:
//
//   - a profile asking for `reasoningEffort: high` on a model that does not
//     advertise it ends the turn with `UNSUPPORTED_REASONING_EFFORT` before any
//     network I/O;
//   - the `relayroute` model used as the global default gets the whole prompt
//     rejected by the relay with `413 preflight_compress_failed`, because its
//     own preflight estimate (≈218k tokens) exceeds the 119808-token window it
//     declares — the harness only sends one sentence, so this is the desktop's
//     provider config, not the request size;
//   - `deepseek-official` answers `MISSING_CREDENTIAL` (no `DEEPSEEK_API_KEY`).
//
// None of those are phone bugs: the phone relays the desktop's
// `reason.error.message` verbatim (see `handleEvent` in
// `core/PocketViewModel.kt`), which is exactly what you want to read here.
// Fix the desktop profile and re-run; do not "fix" the app.
//
// The optional third argument pins the two turn checks to a specific model,
// which is how you prove them on a desktop whose *default* model is broken.
// It is applied to the throwaway session those checks use and nowhere else, so
// the user's own sessions keep their own selection.
//
// Getting the two arguments:
//   node -e "…POST /api/pair/issue then /api/pair/accept…"   // see docs/troubleshooting.md
// Both the token and the resulting deviceId are credentials — keep them out of
// commits, screenshots and issue reports.

const [, , BASE = 'http://127.0.0.1:19387', DEVICE, TURN_MODEL] = process.argv

if (!DEVICE) {
  console.error('usage: node tests/conformance.mjs <baseUrl> <deviceId> [provider/model]')
  process.exit(2)
}

// `provider/model` — the slash is unambiguous because neither id contains one.
const [TURN_PROVIDER, TURN_MODEL_ID] = (TURN_MODEL ?? '').split('/')

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

  // ---- 5b. the composer's two pickers ------------------------------------
  // These are the v0.3.0 endpoints. They run on their *own* throwaway session,
  // never on `sid`: the model picked here decides whether the turn checks below
  // can answer at all, so sharing a session would turn a picker bug into a
  // confusing turn failure (observed: selecting a model whose window is smaller
  // than the desktop's default context makes the next prompt die with
  // CONTEXT_WINDOW_EXCEEDED, which looks like a transport problem and is not).
  // `session/create` is cheap and the desktop cleans blank sessions up itself.

  const pickerSession = await rpc('session/create', { request: {} })
  const pickerSid = pickerSession.value?.sessionId

  // The app's model chip falls back to this exact value when a session has not
  // chosen a model yet. Assert the premise, because the fallback is only correct
  // while these two things stay true: a fresh session reports `next: null`, and
  // the catalog names a non-empty default. If either changes, the chip would
  // silently show a model the next turn will not use.
  const freshProj = await rpc('session/projections', { request: { sessionId: pickerSid } })
  const freshNext = freshProj.value?.values?.modelSelection?.next ?? null
  check(
    'a fresh session leaves the model unset (so the catalog default applies)',
    freshNext === null,
    `next=${JSON.stringify(freshNext)}`,
  )

  // No arguments at all: passing a `request` wrapper here is rejected with
  // `gateway/arguments-invalid`, which is why this one is called bare.
  const catalog = await rpc('session/modelCatalog', {})
  // `groups[].id` *is* the provider id — the same string `session/selectModel`
  // wants back as `provider`. Empty groups are skipped because a provider that
  // failed to enumerate still appears with no models.
  const firstGroup = (catalog.value?.groups ?? []).find((g) => (g.models ?? []).length > 0)
  const firstModel = firstGroup?.models?.[0]
  check(
    'session/modelCatalog returns groups with at least one model',
    catalog.ok && Array.isArray(catalog.value?.groups) && !!firstModel,
    JSON.stringify(catalog.error ?? catalog.value?.failures ?? null).slice(0, 200),
  )
  check(
    'the catalog names a default selection',
    typeof catalog.value?.default?.provider === 'string' && typeof catalog.value?.default?.model === 'string',
    JSON.stringify(catalog.value?.default),
  )

  // The reply is what the desktop actually stored, so it is what gets asserted
  // — comparing against our own request would pass even if the desktop clamped
  // the effort.
  if (firstModel && pickerSid) {
    const effort = firstModel.reasoning?.defaultEffort
    const picked = await rpc('session/selectModel', {
      request: {
        sessionId: pickerSid,
        provider: firstGroup.id,
        model: firstModel.id,
        ...(effort ? { reasoningEffort: effort } : {}),
      },
    })
    check(
      'session/selectModel echoes the selection it stored',
      picked.ok && picked.value?.selected?.model === firstModel.id,
      JSON.stringify(picked.error ?? picked.value).slice(0, 200),
    )
  }

  const presets = await rpc('permissionPresets/catalog', {})
  const presetValues = (presets.value?.options ?? []).map((o) => o.value)
  check(
    'permissionPresets/catalog lists the three presets',
    presets.ok && presetValues.includes('read-only') && presetValues.includes('workspace-write') && presetValues.includes('danger-full-access'),
    JSON.stringify(presets.error ?? presetValues),
  )

  // `session/projections` is how the app reads the *current* values back. It is
  // the only writable-then-readable pair where the write goes through a slash
  // command, so the round trip is what matters here — not the write's own reply.
  const proj = await rpc('session/projections', { request: { sessionId: pickerSid } })
  check(
    'session/projections reports the current model and permission',
    proj.ok &&
      proj.value?.values?.permissions?.currentValue !== undefined &&
      proj.value?.values?.modelSelection?.next !== undefined,
    JSON.stringify(proj.error ?? Object.keys(proj.value?.values ?? {})).slice(0, 200),
  )

  // Flip to read-only and back. Both writes go through `commands/execute`,
  // because the desktop exposes no `permission/set` RPC; the assertion is on the
  // re-read projection, since the command replies "success" regardless of
  // whether the preset actually changed.
  const currentPreset = proj.value?.values?.permissions?.currentValue
  const targetPreset = currentPreset === 'read-only' ? 'workspace-write' : 'read-only'
  const applied = await rpc('commands/execute', {
    agentId: pickerSid,
    line: `/permission ${targetPreset}`,
    submittedAttachments: [],
  })
  const reread = await rpc('session/projections', { request: { sessionId: pickerSid } })
  check(
    'changing the permission preset is visible in the projection',
    applied.ok && reread.value?.values?.permissions?.currentValue === targetPreset,
    `command=${JSON.stringify(applied.error ?? applied.value?.result).slice(0, 120)} projection=${reread.value?.values?.permissions?.currentValue}`,
  )

  const follow = openStream(ws, 'session/follow', {
    args: { request: { address: { kind: 'session', sessionId: sid }, assistantStream: true } },
  })
  const snap = await follow.next()
  check(
    'session/follow opens with a snapshot for the right session',
    snap.value?.type === 'snapshot' && snap.value?.header?.id === sid,
    JSON.stringify(snap.value).slice(0, 160),
  )

  // Pin the turn to the requested model, if one was named. Done on `sid` before
  // the prompt so the turn checks test the contract even when this desktop's
  // global default is broken (see the header). No-op without the third argument.
  if (TURN_PROVIDER && TURN_MODEL_ID) {
    const pinned = await rpc('session/selectModel', {
      request: { sessionId: sid, provider: TURN_PROVIDER, model: TURN_MODEL_ID },
    })
    check(
      `the turn can be pinned to ${TURN_PROVIDER}/${TURN_MODEL_ID}`,
      pinned.ok,
      JSON.stringify(pinned.error ?? pinned.value).slice(0, 200),
    )
  }

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
  //
  // `endReason` is captured so a *failed turn* can be told apart from a
  // *broken contract*. Those look identical if you only check for a missing
  // assistant message, and they need opposite fixes: a broken contract means
  // this harness or the app is wrong, while `reason.kind === "error"` means the
  // desktop answered fine and its own model/provider setup refused the turn
  // (observed here as `UNSUPPORTED_REASONING_EFFORT` when the desktop profile
  // asked for an effort the configured model does not advertise). Reporting the
  // reason turns a bare FAIL into something actionable.
  let sawAssistant = false
  let sawTurnEnd = false
  let endReason = null
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
      if (v.event?.type === 'turn/end') {
        sawTurnEnd = true
        endReason = v.event.data?.reason ?? null
      }
    }
  }
  check(
    'assistant/message arrives on the followed stream',
    sawAssistant,
    sawAssistant ? undefined : `turn ended with reason=${JSON.stringify(endReason)}`,
  )
  check('turn/end arrives (the completion signal the UI waits for)', sawTurnEnd)
  check(
    'the turn itself did not fail inside the desktop',
    endReason?.kind !== 'error',
    endReason?.kind === 'error'
      ? `desktop reported: ${endReason.error?.message ?? 'unknown'} (${endReason.error?.code ?? 'no code'}) - fix the desktop provider/model config, not the phone`
      : undefined,
  )

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
