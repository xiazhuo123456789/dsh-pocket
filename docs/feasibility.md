# 可行性审查报告

> 设计文档第 10 节要求：先做可行性审查，输出技术报告，再决定写什么代码。
> 这是那份报告。所有结论都来自本机实测，不是推测；无法验证的地方明确标注。

## 结论摘要

| 问题 | 结论 |
| --- | --- |
| 最值得借鉴的项目 | 无一个可直接复用，但 `sorsama/deepseek-harness-mobile` 的**功能切分**值得参考 |
| 可复用代码 | 0 行。三个候选仓库都不满足"受支持接口"要求 |
| Desktop 能否用受支持接口接入 | **能**。官方 API Gateway + 已装的远程控制插件 |
| 是否需要独立 Windows 组件 | **不需要**。加了只会增加攻击面 |
| 推荐通信方案 | 复用 `/remote` 门控 + mux WebSocket，不造新协议 |
| 主要风险 | 隧道地址会变；配对设备等于完整控制权；插件版本耦合 |
| MVP 步骤 | 见文末，已全部完成 |

---

## 1. 候选仓库核查

用 GitHub API 实查（2026-10 数据）：

| 仓库 | 许可证 | Stars | 语言 | 最后推送 | 结论 |
| --- | --- | --- | --- | --- | --- |
| `deepseek-ai/deepseek-harness` | MIT | 251,387 | TypeScript | 2026-10-17 | 上游本体，是**协议来源**而非可复用代码 |
| `april-jk/dsh-mobile-suite` | MIT | 12 | — | 2026-08-18 | 仓库仅 372 KB，无实质实现 |
| `sorsama/deepseek-harness-mobile` | MIT | 161 | Kotlin | 2026-10-12 | 4.4 MB，架构可参考，但绑定 LAN 且自造协议 |

**没有复用任何代码。** 原因不是许可证（三个都是 MIT），而是：

- 前两个不构成可用实现
- 第三个假设手机与电脑在同一局域网、并自行定义了一套与官方网关不同的通信方式。
  采用它意味着维护一条与上游并行演进的协议，而不是复用受支持的接口

设计文档明确要求"优先使用官方受支持插件/运行时接口"。复用第三方的私有协议不满足
这一条。

## 2. 本机 Desktop 的实际形态（已实测）

安装：`<安装目录>\`，版本文件 `44.0.0`，桌面壳
`@deepseek-ai/dsh-desktop@0.2.0-rc.2`，运行时清单显示 node 24.21.0 / pnpm 11.7.0。

**关键发现：Desktop 是一个 Electron 壳 + `dsh --profile <name>` CLI + 可插拔 profile。**

```
DeepSeek Harness.exe --expose-internals <app.asar>/dsh/node_modules/@deepseek-ai/dsh-desktop-host/lib/cli.js
```

`resources/runtime/cli/bin/dsh.cmd` 就是这个入口。`dsh web` 起一个 Web GUI（本机
`127.0.0.1:19387`），`dsh headless`、`dsh tui` 是同一运行时的其他前端。

当前 profile（`profiles/desktop`）已安装并启用：

```json
"@linxin666/dsh-remote-web-ui": "0.4.5"
```

它的 `cordis.patch.yml` 里 `autoTunnel: true`、`relay: false`，并记录了选择理由：
relay 方案在 618 KB 文件上 ECONNRESET，首屏 14 个请求里 7 个大请求失败，所以改用
Cloudflare quick tunnel。运行态证据：进程树里
`cloudflared.exe tunnel --url http://127.0.0.1:19387` 的父进程就是 DSH 主进程。

## 3. 有没有受支持的接口（核心问题）

**有，而且是完整的。** 三层：

### 3.1 官方 API Gateway

`@deepseek-ai/dsh-api-gateway` 提供：

- 一元调用：`POST /api/<namespace>/<method>`
- 流式：`WSS /api/remote.mux` 多路复用
- 事件源：`$events` 流，首帧 `{type:"ready", clientId, host:{home}}`
- 审批回执：`$events/result`

命名空间来自各 controller 包：`session`、`skills`、`fileReferences`
（`dsh-api-session-controller`），另有 workspace / job / settings / terminal 等。

### 3.2 远程控制插件的门控

`@linxin666/dsh-remote-web-ui` 在 Gateway 之上加了：

- `/remote/*` 前缀：只有配对设备能过
- 配对：`POST /api/pair/accept` 换设备凭据
- 设备撤销、在线状态、心跳
- 公网隧道与局域网绑定

它同时定义了**三个永远本地的前缀**：`/api/pair`、`/api/update`、
`/api/plugin-manager`。配对设备也无法远程调用它们。

### 3.3 实际验证

在协议层跑通了完整闭环（`tests/conformance.mjs`，13 项全绿）：

```
PASS  unpaired device is refused (403)
PASS  session/list with the paired credential
PASS  pairing surface is unreachable over /remote
PASS  mux WebSocket connects
PASS  $events opens with a ready frame carrying clientId + host.home
PASS  session/create returns a sessionId
PASS  session/follow opens with a snapshot for the right session
PASS  session/prompt is accepted
PASS  assistant/message arrives on the followed stream
PASS  turn/end arrives (the completion signal the UI waits for)
PASS  session/cancel is acknowledged
PASS  $events/result rejects an unknown clientId
PASS  mux survives past the 2s heartbeat interval
```

审批链路单独验证过（`tests/poc-approval.mjs`）：`approval/request` waterfall 帧到达 →
回 `$events/result` → Host 落 `approval/decided outcome:"allowed-once"`。

**结论：MVP 功能表的每一项都有对应的受支持接口。**

## 4. 是否需要独立 Windows 组件

**不需要。** 设计文档说"仅必要时"创建 `windows-agent/`。这里不必要，因为：

1. 远程控制面已经在 Desktop 进程内运行
2. 再加一个中间进程，手机就要信任两个端点、用户就要维护两个组件
3. 没有它无法实现的功能

唯一可能的例外是"电脑主动推送通知到手机"，但那需要常驻进程，且不影响 MVP。真要做，
也应该做成官方插件而不是旁路进程。

## 5. 推荐通信方案

复用现有面，不造新协议：

| 需求 | 方案 |
| --- | --- |
| 配对 | `POST /api/pair/accept`，令牌来自电脑界面 |
| 认证 | `x-dsh-remote-device: <deviceId>` |
| 一元 | `POST /remote/api/<ns>/<method>` |
| 流式 | `WSS /remote/api/remote.mux?device=<id>` |
| 审批 | `$events` waterfall → `$events/result` |
| 公网可达 | 复用电脑已配的 Cloudflare tunnel |

不引入 Relay。设计文档允许自建 Relay，但既然电脑已经能出网（隧道），多一跳中继只增加
延迟和信任点。

## 6. 风险

| 风险 | 严重度 | 处理 |
| --- | --- | --- |
| quick tunnel 主机名每次重启都变 | 高 | 文档明确说明；重新配对即可。固定地址需 named tunnel |
| 配对设备 = 完整控制权 | 高 | 在 README / security-model 里显著说明，不淡化 |
| 依赖第三方插件的线协议 | 中 | `docs/protocol.md` 记录实测帧；`conformance.mjs` 能在插件升级后立刻发现破坏 |
| wire 参数名不统一（`_request` vs `request`） | 中 | 已在代码里逐端点固化，并有注释说明为什么 |
| 审批只能在 mux 存活时回答 | 中 | 通知按钮失效时明确提示，而不是静默丢弃 |
| 中文路径破坏 Gradle | 低 | 文档记录，`android.overridePathCheck` + junction |

## 7. MVP 实施步骤（已完成）

1. ✅ 搭 Android 工具链（SDK 35 / build-tools 35 / Gradle 8.11.1）
2. ✅ 协议层：`GatewayClient`（一元 + mux + 流句柄）、`SessionApi`、`PairingApi`
3. ✅ 用真实帧校准数据模型（`tests/dump-events.mjs` 抓 34 条记录）
4. ✅ 数据层：加密凭据 + DataStore 设置
5. ✅ 状态机：连接、会话、聊天、审批、停止确认
6. ✅ UI：配对 / 会话列表 / 聊天 / 设置
7. ✅ 通知 + 通知内审批操作 + 前台服务
8. ✅ 单元测试（8 项协议解析）
9. ✅ 一致性测试（13 项真实契约）
10. ✅ Release APK（1.72 MB，R8 + 资源收缩）
11. ✅ 文档（架构 / 协议 / 电脑端 / 构建 / 排障 / 安全）

## 8. 无法验证的部分

诚实列出：

- **未在真机上安装运行过。** 本机没有连接 Android 设备（`adb devices` 为空），
  APK 的编译、签名、单元测试、协议一致性都验证了，但 UI 的实际渲染与触摸行为没有
  在物理手机上确认过。
- **未验证插件升级后的兼容性。** 当前对着 `0.4.5` 验证。插件若改帧格式，需要重新
  跑 `conformance.mjs`。
- **未验证 named tunnel 配置**（固定地址）。当前只验证了 quick tunnel。
- **未验证多设备同时配对**时的行为。
- **审批的完整真机流程**（通知 → 点击 → 回执）在协议层验证了，通知 UI 没有真机验证。

这些都需要一台连着电脑的 Android 手机才能补上。
