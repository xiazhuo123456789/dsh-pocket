<p align="center">
  <b>手机负责控制，电脑负责执行</b><br>
  Android 远程控制 DeepSeek Harness · Kotlin + Jetpack Compose · 配对即用 · 开源
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white" alt="platform">
  <img src="https://img.shields.io/badge/lang-Kotlin%202.0-7F52FF?logo=kotlin&logoColor=white" alt="lang">
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="UI">
  <img src="https://img.shields.io/badge/size-R8%201.7MB-0A0A1E" alt="size">
  <img src="https://img.shields.io/badge/license-MIT-green" alt="license">
</p>

---

> **这是什么**：DSH Pocket 是一款 Android 应用。配对之后，你可以用手机向电脑上的
> DeepSeek Harness 提交任务、查看流式回复与实时状态、停止正在运行的任务、处理审批
> 请求，并在任务完成或需要你确认时收到通知。
>
> **它不把模型搬到手机上。** 所有推理、文件操作和命令执行都留在你的电脑上；手机只发送
> 任务、显示结果、传递你的决定。

```
┌──────────────┐   HTTPS / WSS    ┌───────────────────┐   loopback   ┌──────────────────┐
│  DSH Pocket  │ ───────────────▶ │  DeepSeek Harness │ ───────────▶ │  agent / tools   │
│  (Android)   │ ◀─────────────── │  Desktop (Win/mac)│ ◀─────────── │  (your machine)  │
└──────────────┘   paired device  └───────────────────┘              └──────────────────┘
```

---

## ✨ 功能

| 能力 | 说明 |
|---|---|
| **设备配对** | 粘贴配对链接，或从相册选择配对二维码截图 — 零权限扫码 |
| **远程任务** | 自然语言提交、查看流式回复、继续会话、新建会话 |
| **实时状态** | 排队 / 运行 / 等待审批 / 成功 / 失败 / 已取消 |
| **停止任务** | 带确认语义 — 15 秒未确认则明确显示「可能仍在运行」 |
| **审批** | 显示工具名与申请理由，允许一次或拒绝；也可从通知操作 |
| **通知** | 任务完成、需要审批、连接断开 — 三个独立通道 |
| **会话列表** | 按电脑上的工作目录分组成文件夹，默认全部收起 |
| **设置** | 服务器地址、重新连接、撤销本机凭据、通知开关、回答语言 |

> **关于界面**：助手回答是**不套气泡的正文**（带 Markdown 渲染：标题、列表、
> 行内代码、围栏代码块、加粗），只有你自己说的话才套气泡 —— 跟桌面端一致。
> 把每段回答都塞进一个灰方块里会让整屏变成色块，长回答尤其压抑。

> **关于「用简体中文回答」**：电脑端模型有时会自己选择繁体中文，而 Remote API
> 没有暴露「设置回复语言」的接口。所以这一项的实作方式是：开启时在每条消息后
> **附加一段独立的内容段**「请始终使用简体中文（Simplified Chinese）回答。」，
> 你打的原话一个字都不改（可随时关掉）。界面显示时会把这句指令过滤掉，你看到的
> 仍然只有自己写的那句话。

---

## 🔒 状态语义（本应用的核心承诺）

这三条不是文案，是代码里的**不变量**，也是验收测试的对象：

1. **「已发送」意味着电脑确认收到了。** 消息只有在网关返回 `{accepted:true}` 之后才
   从「排队中」变成「已发送」。
2. **「已停止」意味着电脑确认停了。** 点击停止只是把状态置为「已发送停止请求」；只有
   收到该会话的 `turn/end` 事件才会变成「电脑已确认停止」。15 秒内没有确认，界面会
   明确显示「停止请求未被确认，任务可能仍在运行」。
3. **连不上就说连不上。** 网关不可达时界面显示错误，绝不假装请求已经发出。

同样的规则适用于审批：手机显示的待审批操作来自电脑推送的 `approval/request`，你的
选择通过 `$events/result` 送回电脑，界面上的「已处理」以电脑回执为准。

---

## 📦 安装

从 [Releases](../../releases) 下载 `dsh-pocket-0.2.0.apk`，在手机上安装。
Android 8.0（API 26）及以上。每个附件的 SHA-256 列在
[dist/README.md](dist/README.md)，可以核对下载是否完整。

> **先确认版本。** 装好之后到「设置 → 关于 → 版本」看一眼，应当是 `0.2.0 (2)`。
> 如果是 `0.1.0 (1)`，说明装的是旧包，下面 0.2.0 的界面改动一个都不会出现 ——
> 这个版本号是刻意前进的，就是为了让「到底装没装上」有可核对的依据。

首次打开会让你粘贴配对链接。链接来自电脑端 DeepSeek Harness 的「远程控制」面板。

> **关于签名：** 当前 release APK 使用调试密钥签名，便于直接安装体验。自行构建
> 发布版本时请换成你自己的密钥，见 [docs/android-build.md](docs/android-build.md)。

---

## 🛠 从源码构建

前置：JDK 17、Android SDK（platform 35、build-tools 35.0.0）。

```bash
cd android-app
./gradlew :app:assembleRelease   # → app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest # 协议解析测试
```

细节、Windows 上的坑与镜像配置见 [docs/android-build.md](docs/android-build.md)。

### 协议一致性测试

单元测试固定的是帧的**形状**；一致性测试固定的是与真实电脑的**契约**：

```bash
node tests/conformance.mjs http://127.0.0.1:19387 <deviceId>
```

它会在真实电脑上跑完整闭环：未配对设备被拒 → 已配对可列会话 → 配对管理面不可达 →
mux 连接 → `$events` 就绪 → 建会话 → follow 快照 → 提交任务 → 收到 assistant 消息 →
收到 `turn/end` → 取消被确认 → 审批回执校验 → 心跳存活。

最后两项检查会把「链路坏了」和「电脑自己拒了这一轮」区分开——两者表现相同（都没有
assistant 消息）但修法相反。如果输出里出现
`desktop reported: provider "…" model "…" does not support reasoning effort "…"`，那
是电脑端的模型配置问题（`reasoningEffort` 与模型不匹配，错误码
`UNSUPPORTED_REASONING_EFFORT`），应当在电脑端改配置后重跑，而不是改本应用。

---

## 🏗 它如何工作

DSH Pocket **不实现自己的协议**。它复用 DeepSeek Harness 已经提供的远程控制面：

| 能力 | 使用的接口 |
| --- | --- |
| 配对 | `POST /api/pair/accept`（一次性配对令牌） |
| 之后所有调用 | `/remote/api/*`，凭据放在 `x-dsh-remote-device` |
| 一元调用（建会话、提交任务、停止） | `POST /remote/api/<namespace>/<method>` |
| 事件流与会话流 | `WSS /remote/api/remote.mux` 上的多路复用流 |
| 审批 | `$events` 上的 waterfall 帧 → 回包到 `$events/result` |

配对成功后，手机持有的是一个**设备凭据**。它由电脑签发、可以随时在电脑上撤销，并且
不能用来调用配对管理面——`/api/pair`、`/api/update`、`/api/plugin-manager` 在远程侧
永远返回 403，这是 DeepSeek Harness 的设计而不是本应用的取舍。

### 为什么手机端不需要 Windows 组件

远程控制面已经存在于 DeepSeek Harness Desktop 中（由 `@linxin666/dsh-remote-web-ui`
插件挂在官方 API Gateway 上）。因此本仓库**没有** `windows-agent/` 或
`harness-plugin/`：增加一个中间进程只会多一个需要信任、需要升级、需要排障的环节。

你需要在电脑上做的只有一件事：在 DeepSeek Harness 的「远程控制」面板里生成配对
链接或二维码。详见 [docs/windows-setup.md](docs/windows-setup.md)。

---

## 🔐 安全模型

- 设备凭据由电脑签发，存于 Android EncryptedSharedPreferences（AES-256-GCM）
- 电脑端可随时撤销任一设备；撤销后该设备立即失去全部远程能力
- 手机端「断开并清除」只删本机凭据，彻底撤销必须到电脑上操作
- 传输走 HTTPS/WSS；局域网直连时允许明文（见 `network_security_config.xml` 注释）
- 应用不做本地持久化会话内容，`allowBackup=false`
- **配对的设备等价于完整控制权。** 电脑上的对话、会话、设置、凭据、预设全部对它可见。
  这不是本应用的实现选择，而是 DeepSeek Harness 远程控制面的既定语义。请只配对
  你自己的设备。

本应用不宣称提供沙箱、审批或加密可以消除全部风险。详见
[docs/security-model.md](docs/security-model.md)。

---

## 📁 仓库结构

```
dsh-pocket/
├── android-app/          Kotlin + Jetpack Compose 应用
├── protocol/             协议说明（Markdown，机器可读示例）
├── tests/                真实电脑上的协议一致性测试（Node，无依赖）
├── docs/                 架构、电脑端配置、构建、排障、安全模型
└── dist/                 构建产物（GitHub Release 附件）
```

设计文档建议的 `windows-agent/` 与 `harness-plugin/` 没有创建，因为可行性审查确认
DeepSeek Harness 已有受支持的远程控制面，无需额外组件。原因记录在
[docs/architecture.md](docs/architecture.md)。

---

## 📚 文档

| 文档 | 内容 |
| --- | --- |
| [docs/feasibility.md](docs/feasibility.md) | 可行性审查报告：候选仓库核查、接口验证、风险、未验证项 |
| [docs/architecture.md](docs/architecture.md) | 分层、数据流、状态机、为什么没有 Windows 组件 |
| [docs/protocol.md](docs/protocol.md) | 线协议：帧格式、端点、错误码、审批回执 |
| [docs/windows-setup.md](docs/windows-setup.md) | 电脑端需要做什么（只有一步） |
| [docs/android-build.md](docs/android-build.md) | 构建环境、镜像、Windows 中文路径等坑 |
| [docs/troubleshooting.md](docs/troubleshooting.md) | 配对失败、隧道重启、403、状态不更新 |
| [docs/security-model.md](docs/security-model.md) | 威胁模型与不承诺什么 |
| [docs/sources.md](docs/sources.md) | **每一条外部事实的来源**，以及哪些是观察、哪些是我们的判断 |

### 关于引用与署名

本项目的线协议事实**不是**从博客或别人的应用里抄来的，而是从开发机上真实安装的
DeepSeek Harness 里读出来、再用运行中的进程验证的。哪些是观察所得、哪些是本项目的
设计判断、参考了哪些公开仓库与规范，全部逐条列在
[docs/sources.md](docs/sources.md)。

**本仓库没有拷贝任何第三方源代码。**上游项目在 `docs/sources.md` 第 3 节署名，是出于
对影响的诚实说明，不是许可义务。如果发现任何一处来源标注错误或措辞抄袭，请开 issue——
来源标注错误在本项目里按 bug 处理。

---

## 📊 当前状态

`v0.2.0` 是第二个可安装的版本（`v0.1.0` 是首个）。诚实说明验证到了什么程度：

**已验证**

- Android 工程编译通过（debug + release），R8 收缩后 1.75 MB
- 22 项单元测试通过：8 项协议解析（用真实抓包帧做夹具）+ 6 项回复语言指令过滤
  + 8 项 Markdown 解析
- 12 项与真实电脑的协议一致性测试通过（配对、列会话、建会话、提交、收流、
  完成信号、取消、审批回执校验、心跳）
- 审批链路端到端验证：waterfall 帧 → 回执 → 电脑落 `approval/decided`
- 回复语言指令端到端验证：附加「请用简体中文回答」后，真实电脑的回答里
  繁体专用字集合为空（`traditional-only chars found = []`）

**未验证**

- **未在物理手机上安装运行。** 开发环境没有连接 Android 设备，UI 的实际渲染与
  触摸交互没有真机确认。0.2.0 的界面改版（正文式回答、Markdown 渲染、输入区分层）
  因此只有编译期与单元测试层面的保证，观感需要你装上看。
- **「模型真的产出回复」这一环没有在最后一次回归里通过。** 一致性测试的 14 项检查中
  有 2 项失败，原因是测试所用电脑的模型配置不可用（`reasoningEffort: high` 与
  `gpt-5.2-pro` 不匹配，错误码 `UNSUPPORTED_REASONING_EFFORT`）；同一台电脑更早的
  会话确实产出过 49 条 assistant 消息，所以链路本身是通的，缺的是当前模型配置下的
  一次完整回归。这两个失败被如实报出而不是被跳过——见上面「协议一致性测试」一节。
- 插件升级后的兼容性（当前对着 `@linxin666/dsh-remote-web-ui@0.4.5`）
- 多设备同时配对
- 通知按钮在真机上的完整流程

细节见 [docs/feasibility.md](docs/feasibility.md) 第 8 节。

---

## 📄 许可证

MIT，见 [LICENSE](LICENSE)。第三方组件与各自许可证见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 🤝 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题请按 [SECURITY.md](SECURITY.md) 私下报告。
