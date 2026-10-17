// Dump raw session/follow frames so the Android UI can be written against the
// real event schema instead of guesses.
// usage: node tools/dump-events.mjs <baseUrl> <deviceId> [promptText]
const base = (process.argv[2] || '').replace(/\/+$/, '');
const device = process.argv[3] || '';
const promptText = process.argv[4] || 'Reply with exactly: PONG';

if (!base || !device) {
  console.error('usage: node tools/dump-events.mjs <baseUrl> <deviceId> [promptText]');
  process.exit(2);
}

const HDR = { 'x-dsh-remote-device': device };

async function rpc(endpoint, args) {
  const res = await fetch(`${base}/remote/api/${endpoint}`, {
    method: 'POST',
    headers: { ...HDR, 'content-type': 'application/json' },
    body: JSON.stringify({
      type: 'client-request',
      rpcId: `d${Date.now()}-${Math.random().toString(16).slice(2)}`,
      method: endpoint,
      payload: { args },
    }),
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { throw new Error(`non-json ${res.status}: ${text.slice(0, 300)}`); }
  const r = json.result;
  if (!r) throw new Error(`no result: ${text.slice(0, 300)}`);
  if (r.ok !== true) throw new Error(`${endpoint} failed: ${JSON.stringify(r.error)}`);
  return r.value;
}

const log = [];
function record(kind, payload) {
  log.push({ t: new Date().toISOString(), kind, payload });
}

const wsUrl = `${base.replace(/^http/, 'ws')}/remote/api/remote.mux?device=${encodeURIComponent(device)}`;
const ws = new WebSocket(wsUrl);
const streams = new Map();
let opened = false;

function send(obj) { ws.send(JSON.stringify(obj)); }

function openStream(endpoint, args) {
  const streamId = crypto.randomUUID();
  send({ type: 'open', streamId, endpoint, payload: { args } });
  return streamId;
}

ws.addEventListener('open', () => { opened = true; });

ws.addEventListener('message', (ev) => {
  let f;
  try { f = JSON.parse(ev.data); } catch { return; }
  record('frame', f);
  if (f.type === 'item' && streams.has(f.streamId)) {
    const v = f.value;
    if (v && v.type === 'snapshot') {
      for (const rec of v.records || []) record('snapshot-record', rec);
    }
  }
  if (f.type === 'end' || f.type === 'error') {
    if (streams.has(f.streamId)) streams.delete(f.streamId);
  }
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  while (!opened) await sleep(20);
  console.log('[mux] connected');

  // Subscribe to the forwarded-event source (approvals + session list churn).
  const evStream = openStream('$events', {});
  streams.set(evStream, '$events');

  const created = await rpc('session/create', { request: {} });
  const sessionId = created.sessionId;
  console.log('[create]', sessionId);

  const followId = openStream('session/follow', {
    request: { address: { kind: 'session', sessionId }, assistantStream: true },
  });
  streams.set(followId, 'session/follow');

  await sleep(600);
  await rpc('session/prompt', {
    request: {
      requestId: crypto.randomUUID(),
      sessionId,
      mode: 'queue',
      content: [{ type: 'text', text: promptText }],
    },
  });
  console.log('[prompt] sent');

  await sleep(9000);
  await rpc('session/cancel', { request: { sessionId } }).catch(() => {});
  await sleep(400);

  const fs = await import('node:fs');
  fs.writeFileSync('tools/frames.json', JSON.stringify(log, null, 2));
  console.log(`[done] ${log.length} records -> tools/frames.json`);
  ws.close();
  process.exit(0);
})().catch((e) => {
  console.error('[fatal]', e.message);
  process.exit(1);
});
