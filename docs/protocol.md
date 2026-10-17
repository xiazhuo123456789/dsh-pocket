# 线协议

本文件记录 DSH Pocket 与 DeepSeek Harness 之间实际跑在网线上的东西。所有例子都来自
真实抓包，不是设计稿。

## 认证

配对成功后，电脑签发一个设备 ID。之后每个请求都带：

```
x-dsh-remote-device: <deviceId>
```

WebSocket 握手无法带自定义 header，所以设备 ID 走 query，并且电脑在转发前会把它剥掉：

```
wss://<host>/remote/api/remote.mux?device=<deviceId>
```

没有凭据、或凭据已被撤销时，`/remote/*` 一律返回：

```json
HTTP 403
{"code":"unpaired","message":"this device is not paired with the desktop","details":{"issues":[]}}
```

## 一元调用

```
POST /remote/api/<namespace>/<method>
Content-Type: application/json

{"type":"client-request","rpcId":"<任意唯一串>","method":"<namespace>/<method>","payload":{"args":{...}}}
```

成功：

```json
{"type":"server-response","rpcId":"...","result":{"ok":true,"value":{...}}}
```

失败：

```json
{"type":"server-response","rpcId":"...","result":{"ok":false,"error":{"code":"gateway/arguments-invalid","message":"...","details":{}}}}
```

`code` 是稳定的命名空间字符串，可以据此分支，不要解析 `message`。

### 参数名的坑

不同端点的 wire 参数名**不一致**，必须逐个确认：

| 端点 | 外层参数名 |
| --- | --- |
| `session/list` | `_request`（下划线！） |
| `session/create` | `request` |
| `session/prompt` | `request` |
| `session/cancel` | `request` |
| `session/follow` | `request` |

传错会得到：

```
gateway/arguments-invalid
typert gateway: session/create: args fields do not match the descriptor:
missing "request"; unexpected "_request"
```

## 流式：`/remote/api/remote.mux`

一条 WebSocket 上多路复用任意数量的逻辑流。

### 客户端 → 服务端

```json
{"type":"open","streamId":"<uuid>","endpoint":"<namespace>/<method>","payload":{"args":{...}}}
{"type":"item","streamId":"<uuid>","value":{...}}
{"type":"end","streamId":"<uuid>"}
{"type":"cancel","streamId":"<uuid>"}
```

### 服务端 → 客户端

```json
{"type":"item","streamId":"<uuid>","value":{...}}
{"type":"end","streamId":"<uuid>"}
{"type":"error","streamId":"<uuid>","error":{"code":"...","message":"...","details":{}}}
```

服务端**严格校验**帧：多一个字段就判非法并断开。不要往上发自定义字段。

心跳：电脑每 2 秒发 Ping，未回 Pong 则断开连接。

## 端点

### `$events`（全局事件流）

```json
{"type":"open","streamId":"...","endpoint":"$events","payload":{"args":{}}}
```

首帧：

```json
{"type":"ready","clientId":"9a36421a-7a29-4f4a-9cfa-aac744d79496","host":{"home":"C:\\Users\\<你的用户名>"}}
```

`clientId` 必须保留，回答审批时要用。

后续帧：

```json
{"type":"emit","event":"api-session/status","args":["session-...",true]}
{"type":"emit","event":"api-session/added","args":[...]}
{"type":"emit","event":"api-session/activity","args":[...]}
{"type":"waterfall","event":"approval/request","eventId":"59ae35e9-...","agentId":"session-...","request":{"toolName":"pwsh","callId":"call_00_97...","reason":"escalate sandbox to danger-full-access: ..."}}
```

`api-session/status` 的 `args[1]` 是布尔值，是运行状态灯的权威来源。

### `session/follow`

```json
{"type":"open","streamId":"...","endpoint":"session/follow",
 "payload":{"args":{"request":{"address":{"kind":"session","sessionId":"session-..."},"assistantStream":true}}}}
```

首帧是快照：

```json
{"type":"snapshot",
 "header":{"version":4,"id":"session-...","cwd":"C:\\...","isSeeded":false,"agentPreset":"standard"},
 "cursor":2,
 "records":[{"type":"event","event":{"type":"user/message","seq":1,"time":10,"data":{...}}}]}
```

后续帧：

```json
{"type":"event","event":{"type":"turn/end","seq":9,"time":1,"data":{"turn":1,"reason":{"kind":"completed"}}}}
{"type":"start","attemptId":"...","revision":1,"turn":1,"step":1,"startedAfterSeq":12}
{"type":"chunk","attemptId":"...","revision":1,"index":0,"chunk":{"type":"text-delta","index":0,"text":"PO"}}
{"type":"end","attemptId":"...","revision":1,"outcome":{"kind":"committed","eventType":"assistant/message","seq":42}}
```

`chunk.chunk.type` 的取值：`block-start` / `text-delta` / `usage` / `block-end` / `finish`。
只有 `text-delta` 带 `text`。

### 事件类型与含义

| 事件 | 含义 | 手机怎么用 |
| --- | --- | --- |
| `turn/start` | 一轮开始 | 状态 → 运行中 |
| `turn/end` | 一轮结束，`data.reason.kind` | **完成/失败的唯一权威信号** |
| `assistant/message` | 完整回复 | 气泡（流式结束后落定） |
| `user/message` | 用户消息（`data.source.kind == "user"` 才是自己发的） | 用户气泡 |
| `tool/call` / `tool/result` | 工具调用与结果 | 折叠的工具气泡 |
| `approval/asked` / `approval/decided` | 审批被问/被决定 | 卡片出现与消失 |
| `session/title` | 标题更新 | 标题栏 |
| `agent/inbox/spliced` | 收件箱变更 | 忽略 |

## 审批回执

```
POST /remote/api/$events/result

{"type":"client-request","rpcId":"...","method":"$events/result",
 "payload":{"args":{"clientId":"<来自 ready 帧>","eventId":"<来自 waterfall 帧>",
                    "outcome":{"kind":"result","value":"allowed-once"}}}}
```

`outcome` 的三种形态：

```json
{"kind":"next"}                                   // 交给下一个监听器，我不处理
{"kind":"result","value":"allowed-once"}          // 允许这一次
{"kind":"result","value":"rejected"}              // 拒绝
{"kind":"rejected","error":"..."}                 // 以错误关闭
```

`clientId` 必须是**当前活跃**的 `$events` 流。用一个已经断开的 `clientId` 会得到：

```
typert gateway: Remote event result identifies no active event stream
```

这正是手机端冷启动后无法回答旧审批的原因：`clientId` 只在 socket 存活期间有效。

## 永远不可远程访问的前缀

```
/api/pair
/api/update
/api/plugin-manager
```

即使设备已配对，`/remote/api/pair/*` 也返回：

```json
HTTP 403
{"code":"forbidden","message":"api/pair stays physically local and stays unreachable from a paired remote desktop"}
```

配对本身是唯一例外：未配对状态下可以 `POST /api/pair/accept`，因为那正是配对的动作。

## 配对流程

1. 电脑（loopback）`POST /api/pair/issue` → `{url, token, expiresAt, publicBaseUrl}`，
   `url` 形如 `https://<host>/pair-accept?pair=<token>`
2. 手机 `POST /api/pair/accept` `{"token":"<token>"}` → `{ok:true, deviceId}`
   并 `Set-Cookie: dsh_pair=<deviceId>; Path=/; HttpOnly`
3. 之后所有调用用 `x-dsh-remote-device: <deviceId>`

配对令牌一次性、有有效期。`/pair-accept` 是给浏览器用的页面；手机直接调
`/api/pair/accept` 即可。
