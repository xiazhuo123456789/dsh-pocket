// Verify the approval path end-to-end: subscribe $events, trigger a tool that
// needs approval, then answer with $events/result.
// usage: node tools/poc-approval.mjs <baseUrl> <deviceId>
const base = (process.argv[2] || '').replace(/\/+$/, '');
const device = process.argv[3] || '';
if (!base || !device) {
  console.error('usage: node tools/poc-approval.mjs <baseUrl> <deviceId>');
  process.exit(2);
}
const HDR = { 'x-dsh-remote-device': device };

async function rpc(endpoint, args) {
  const res = await fetch(`${base}/remote/api/${endpoint}`, {
    method: 'POST',
    headers: { ...HDR, 'content-type': 'application/json' },
    body: JSON.stringify({
      type: 'client-request',
      rpcId: `a${Date.now()}-${Math.random().toString(16).slice(2)}`,
      method: endpoint,
      payload: { args },
    }),
  });
  const text = await res.text();
  const json = JSON.parse(text);
  const r = json.result;
  if (!r) throw new Error(`no result: ${text.slice(0, 300)}`);
  if (r.ok !== true) throw new Error(`${endpoint}: ${JSON.stringify(r.error)}`);
  return r.value;
}

const ws = new WebSocket(`${base.replace(/^http/, 'ws')}/remote/api/remote.mux?device=${encodeURIComponent(device)}`);
const streams = new Map();
let clientId = null;
let opened = false;

ws.addEventListener('open', () => { opened = true; });
ws.addEventListener('message', async (ev) => {
  const f = JSON.parse(ev.data);
  if (f.type === 'item' && f.value && f.value.type === 'ready') {
    clientId = f.value.clientId;
    console.log('[ready] clientId =', clientId, 'home =', f.value.host && f.value.host.home);
  }
  if (f.type === 'item' && f.value && f.value.type === 'event') {
    const t = f.value.event && f.value.event.type;
    if (t === 'approval/asked' || t === 'approval/decided' || t === 'tool/call' || t === 'tool/result') {
      console.log('[event]', t, JSON.stringify(f.value.event.data).slice(0, 400));
    }
  }
  if (f.type === 'item' && f.value && f.value.type === 'waterfall') {
    console.log('[waterfall]', JSON.stringify(f.value).slice(0, 600));
    if (f.value.event === 'approval/request' && clientId) {
      // Approve once.
      const outcome = { kind: 'result', value: 'allowed-once' };
      try {
        const v = await rpc('$events/result', { clientId, eventId: f.value.eventId, outcome });
        console.log('[answered] allow-once ->', JSON.stringify(v));
      } catch (e) {
        console.log('[answer-failed]', e.message);
      }
    }
  }
  if (f.type === 'error') console.log('[stream-error]', JSON.stringify(f.error));
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  while (!opened) await sleep(20);
  const evStream = crypto.randomUUID();
  ws.send(JSON.stringify({ type: 'open', streamId: evStream, endpoint: '$events', payload: { args: {} } }));
  streams.set(evStream, '$events');
  await sleep(500);

  const { sessionId } = await rpc('session/create', { request: {} });
  console.log('[create]', sessionId);
  const followId = crypto.randomUUID();
  ws.send(JSON.stringify({
    type: 'open', streamId: followId, endpoint: 'session/follow',
    payload: { args: { request: { address: { kind: 'session', sessionId }, assistantStream: true } } },
  }));
  streams.set(followId, 'follow');
  await sleep(400);

  await rpc('session/prompt', {
    request: {
      requestId: crypto.randomUUID(),
      sessionId,
      mode: 'queue',
      content: [{ type: 'text', text: process.argv[4] || 'Run the shell command: echo hello-from-approval-test' }],
    },
  });
  console.log('[prompt] sent, waiting for approval...');

  await sleep(20000);
  await rpc('session/cancel', { request: { sessionId } }).catch(() => {});
  await sleep(300);
  ws.close();
  process.exit(0);
})().catch((e) => { console.error('[fatal]', e.message); process.exit(1); });
