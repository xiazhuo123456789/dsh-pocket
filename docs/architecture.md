# 架构

## 一句话

手机是**瘦客户端 + 状态镜像**：它不做任何决策，只发送意图并如实显示电脑告诉它的结果。

## 分层

```
android-app/app/src/main/java/dev/dshpocket/
├── protocol/     线协议。唯一知道 JSON 长什么样的地方
│   ├── GatewayClient.kt   HTTP 一元调用 + mux WebSocket + 流句柄
│   ├── SessionApi.kt      会话相关的端点
│   ├── PairingApi.kt      配对（唯一走 /api/pair 的地方）
│   ├── Types.kt           FollowEvent / PromptMode / ApprovalRequest
│   ├── RemoteResult.kt    RemoteResult / RemoteError / RemoteCallException
│   └── Json.kt            lenient JSON 配置与顶层扩展函数
├── data/         持久化
│   ├── CredentialStore.kt 设备凭据（EncryptedSharedPreferences）
│   └── SettingsStore.kt   通知开关等（DataStore）
├── core/         状态机。唯一改变 UI 状态的地方
│   ├── UiState.kt         不可变状态类型
│   ├── PocketViewModel.kt 连接、会话、聊天、审批的全部状态迁移
│   ├── ChatMapping.kt     事件 → 气泡
│   └── ApprovalBridge.kt  通知按钮 → ViewModel 的窄接口
├── ui/           Jetpack Compose，无业务逻辑
├── notify/       通知与通知操作
├── service/      前台服务（任务运行时保活）
└── qr/           二维码解码（zxing，仅解码本地图片）
```

依赖方向是单向的：`ui → core → protocol`。`protocol` 不知道 `core` 存在，`core` 不知道
Compose 存在。这样协议测试可以完全不碰 Android 框架。

## 数据流

### 一元调用（提交任务、停止、建会话）

```
UI 事件 → ViewModel.send() → SessionApi.prompt() → GatewayClient.call()
        → POST /remote/api/session/prompt
        ← {result:{ok:true,value:{accepted:true}}}
        → 状态从 Queued 变成 Sent
```

### 流式（回复、状态）

```
GatewayClient.ensureMux()  ── WSS /remote/api/remote.mux?device=<id>
        │
        ├── openStream("session/follow")  → 该会话的事件日志 + assistant 流
        └── openStream("$events")         → 全局事件（运行状态、审批请求）
```

`$events` 的第一个帧是 `{type:"ready", clientId, host:{home}}`。`clientId` 必须留住：
回答审批时要把它原样放进 `$events/result`，否则电脑拒绝。

### 审批

```
$events 收到 {type:"waterfall", event:"approval/request", eventId, agentId, request}
  → ViewModel 放入待审批列表 → UI 显示卡片 + 发通知
  → 用户点「允许一次」/「拒绝」（或在通知上直接点）
  → GatewayClient.answerEvent(eventId, outcome)
  → POST /remote/api/$events/result  {clientId, eventId, outcome}
  → 电脑落 approval/decided，UI 移除卡片
```

审批结果只有两个值：`allowed-once` 和 `rejected`。没有「始终允许」，没有规则记忆。
这是电脑端的契约，不是本应用的简化。

## 状态机：为什么停止需要确认

设计文档第 7 节要求「停止请求需验证实际取消」。所以停止不是一个布尔值，而是四态：

```
None ──stop()──▶ Requested ──turn/end──▶ Confirmed
                     │
                     └──15s 无 turn/end──▶ Unconfirmed
```

- `Requested` 只表示 HTTP 请求返回了 `{accepted:true}`
- `Confirmed` 只在收到该会话的 `turn/end` 事件时置位
- `Unconfirmed` 明确告诉用户「任务可能仍在运行」

没有第五条路径。UI 不能自己把状态改成已停止。

同理，消息的 `Queued → Sent` 迁移只发生在 `prompt()` 返回 `accepted:true` 之后；
网络失败时进入 `isError`，而不是乐观地当成已发送。

## 连接生命周期

`PocketViewModel` 持有唯一的 `GatewayClient`。连接状态有四相：
`Disconnected → Connecting → Connected`，任何失败进入 `Error` 并保留错误文本。

- 配对成功后 `connect(paired)` 启动 `$events` 订阅
- 断线时 `connectionLost` 通知（可在设置里关）
- `MainActivity.onStart` 会在已配对但未连接时重连
- 前台服务在「有任务在跑」时启动，任务全部结束后停止

### 退避重连与「状态未知」

`GatewayClient` 检测到 mux socket 断开（`onFailure` / `onClosed`）时，会把同一个
错误广播给所有已开流，并回调 `onCarrierLost`。`PocketViewModel` 收到后做三件事：

1. 把连接相位设为 `Error`，让横幅显示真实原因；
2. 把 `chat.status` 中仍在进行的那几态（`Running` / `Queued` / `AwaitingApproval`）
   降级为 `Unknown`，并清掉已失效的审批卡片和流式气泡；
3. 按 1s → 2s → 4s → 8s → 16s → 30s 退避重连（封顶 30 秒），成功后清零计数、
   重新拉列表并重新订阅当前打开的会话。

**为什么必须引入 `Unknown`**：运行状态唯一的来源是 `api-session/status`，通道断了就
再也收不到，最后一次读数会永久冻结在快照里。若继续显示「运行中」，屏幕上就同时存在
「● 运行中」和「连接失败」两句互相矛盾的自述——这比承认不知道更糟。

`disconnectInternal()` 会取消重连任务并清零计数，所以用户主动断开或取消配对之后，
不会出现「我明明断了它自己又连上」。

连接状态与凭据分离：凭据在 `CredentialStore`，连接在 `PocketViewModel`。所以杀进程
重开后状态可以恢复（重新连接 + 重新 follow 会话），凭据不会丢。

## 为什么没有 windows-agent / harness-plugin

设计文档第 10 节要求先做可行性审查，并明确「目录应按审查结果调整，不为凑结构创建
无用组件」。审查结论：

1. DeepSeek Harness Desktop 已经运行官方 API Gateway（`/api` 一元 + `/api/remote.mux`
   多路复用 WebSocket）。
2. 已安装的 `@linxin666/dsh-remote-web-ui` 插件在此之上提供了配对、设备凭据、远程门控
   `/remote` 前缀、以及公网隧道。
3. 这套面覆盖了 MVP 的全部功能表：配对、任务、状态、停止、审批、通知。

因此再加一个 Windows 中间进程只会增加攻击面与维护成本，而不增加能力。**本仓库的
Windows 端工作量是零**——用户只需要在现有面板里生成配对链接。

如果将来需要「电脑端主动推送通知到手机」或「多电脑聚合」，那时才需要一个常驻组件；
届时它应该是官方插件形式，而不是绕过受支持接口的旁路。

## 已知限制

- 公网隧道（Cloudflare quick tunnel）的主机名每次 DeepSeek Harness 重启都会变，手机
  需要重新配对。固定地址需要配置 named tunnel。
- 普通通知事件在断线重连后不重放；重连后靠重新 follow 会话恢复可见状态。
- 审批只能在 mux socket 存活时回答。进程被杀后，通知上的审批按钮会提示「审批已失效」。
- 不做会话内容的本地持久化（`allowBackup=false`），重开应用后聊天记录从电脑重新拉取。
