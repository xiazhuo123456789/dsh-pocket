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
| **设备配对** | 打开摄像头扫电脑屏幕上的二维码；也可从相册选截图，或直接粘贴链接 |
| **远程任务** | 自然语言提交、查看流式回复、继续会话、新建会话 |
| **实时状态** | 排队 / 运行 / 等待审批 / 成功 / 失败 / 已取消 |
| **停止任务** | 带确认语义 — 15 秒未确认则明确显示「可能仍在运行」 |
| **审批** | 显示工具名与申请理由，允许一次或拒绝；也可从通知操作 |
| **通知** | 任务完成、需要审批、连接断开 — 三个独立通道 |
| **会话列表** | 按电脑上的工作目录分组成文件夹，默认全部收起 |
| **模型选择** | 输入框上方直接切换模型与推理档位，值以电脑为准 |
| **权限选择** | 同上切换 `read-only` / `workspace-write` / `danger-full-access` |
| **设置** | 服务器地址、重新连接、撤销本机凭据、通知开关、回答语言 |
| **工具过程** | 工具调用与结果合为一行：工具名 + 参数摘要 + 可展开的输出 |
| **桌宠** | 内置 16 段动画（3444 帧，随 APK 离线可用），跟着电脑状态切换，可调大小 |

> **关于两个选择器**：模型和权限都**读自电脑的投影**，不是手机自己记的。因为你
> 也可以在电脑上改这两项，手机若显示自己上次点的那个，就会承诺一个下一个 turn
> 根本不会用的模型。投影还没到时两个胶囊显示「未知」并置灰 —— 不知道就得长得像
> 不知道。权限尤其如此：`/permission` 命令返回 `success` 只说明命令跑了，所以
> 发完之后会重读投影，以电脑报回来的值为准；若没变成请求的档位，会把命令自己的
> 文字显示出来，而不是安静地假装成功。
>
> 选择模型分两步（先选模型、再选推理档位），因为三元组在手机上一屏放不下；而且
> 实测这台电脑的 127 个模型里有 23 个完全不带 `reasoning` 字段，给它们发一个档位
> 会让电脑在发送后整条拒掉。

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

从 [Releases](../../releases) 下载 `dsh-pocket-0.10.0.apk`，在手机上安装。
Android 8.0（API 26）及以上。每个附件的 SHA-256 列在
[dist/README.md](dist/README.md)，可以核对下载是否完整。

> **体积**：0.5.0 起是 11.6 MiB（0.3.0 只有 1.8 MiB）。多出来的全是扫码 ——
> CameraX 加内置的 ML Kit 条码模型。构建已剔掉 x86/x86_64 两份原生库，
> 实机（arm64）正常；模拟器上扫码不可用，相册选图仍可用。

> **先确认版本。** 装好之后到「设置 → 关于 → 版本」看一眼，应当是 `0.10.0 (11)`。
> 如果不是，说明装的是旧包。这个版本号是刻意前进的，就是为了让「到底装没装上」
> 有可核对的依据。之前连续几轮修复都停在同一个 `versionCode`，结果同一批问题被
> 反复回报，而代码其实早就改好了；那次的教训写进了
> `android-app/app/build.gradle.kts` 的注释里。

首次打开会让你扫电脑屏幕上的二维码（也可以粘贴链接或从相册选截图）。配对链接来自
电脑端 DeepSeek Harness 的「远程控制」面板。

> **为什么会「过一段时间就自动取消配对」。** 电脑端默认用 Cloudflare 的临时隧道
> 暴露服务，这种地址**每次桌面端重启都会换一个新的域名**。手机上保存的仍是旧地址，
> 于是表现为「连不上 / 像是被取消配对了」。实际上配对关系没有被撤销 —— 到电脑上
> 重新打开远程面板，用新的二维码或链接再配一次即可，不需要卸载重装。想要固定地址，
> 需要在电脑端换成自定义域名或固定隧道（本应用侧无法解决，因为它不知道新地址）。

> **关于签名：** 当前 release APK 使用调试密钥签名，便于直接安装体验。自行构建
> 发布版本时请换成你自己的密钥，见 [docs/android-build.md](docs/android-build.md)。

---

## 🛠 从源码构建

前置：JDK 17、Android SDK（platform 35、build-tools 35.0.0）。

```bash
cd android-app
./gradlew :app:assembleRelease   # → app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest # 协议解析 / Markdown / 工具行 / 指令过滤
```

细节、Windows 上的坑与镜像配置见 [docs/android-build.md](docs/android-build.md)。

> **构建这台机器上的一个坑**：加了 CameraX + ML Kit 之后，debug 变体要合并的 dex
> 一下子变大，D8 在 `mergeExtDexDebug` 里直接 `OutOfMemoryError: Java heap space`。
> 所以 `android-app/gradle.properties` 的 `org.gradle.jvmargs` 是 1792m 而不是常见的
> 2048m 以上 —— 这台机器只有 16 GB，构建时余量不到 3 GB，写大了反而会拖垮系统。
> 如果你的机器内存更大，把这一行调高会更快。

### 协议一致性测试

单元测试固定的是帧的**形状**；一致性测试固定的是与真实电脑的**契约**：

```bash
node tests/conformance.mjs http://127.0.0.1:19387 <deviceId>
```

它会在真实电脑上跑完整闭环：未配对设备被拒 → 已配对可列会话 → 配对管理面不可达 →
mux 连接 → `$events` 就绪 → 建会话 → 模型目录与权限档位 → 切换模型 → 切换权限并回读投影 →
follow 快照 → 提交任务 → 收到 assistant 消息 → 收到 `turn/end` → 取消被确认 → 审批回执校验
→ 心跳存活，共 22 项。

那两项关于「这一轮真的跑完」的检查会把「链路坏了」和「电脑自己拒了这一轮」区分开——两者
表现相同（都没有 assistant 消息）但修法相反。失败信息里带着电脑返回的错误码：

| 错误码 | 含义 | 修法 |
| --- | --- | --- |
| `UNSUPPORTED_REASONING_EFFORT` | 配置要求的推理强度该模型不支持 | 改电脑 `agent-default-model` 的 `reasoningEffort` |
| `MISSING_CREDENTIAL` | 该 provider 没有 API key | 在电脑上存对应密钥 |
| `CONTEXT_WINDOW_EXCEEDED` / `preflight_compress_failed` | 电脑估算的上下文超过该模型窗口 | 换默认模型，或调整那一路的预检 |
| `SERVER` | 上游线路中途断流 | 换线路 |

如果电脑的**默认**模型暂时用不了，可以加第三个参数把那一轮固定到指定模型，从而在不改电脑
配置的前提下证明手机端与协议是好的：

```bash
node tests/conformance.mjs http://127.0.0.1:19387 <deviceId> buddy/deepseek-v4.1-flash
```

它只在测试自建的一次性会话上生效，不碰你自己的会话。

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
| [docs/pet.md](docs/pet.md) | 桌宠：状态映射、素材来源与打包格式、怎么重新打包 |
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

`v0.10.0` 是最新的可安装版本（`v0.1.0` 是首个，`v0.2.0` 把回答改成了正文式渲染，
`v0.3.0` 加了模型与权限选择器，`v0.4.0` 加了摄像头扫码并重做了工具行的呈现，
`v0.4.1` 修掉了 0.4.0 的相机黑屏，`v0.5.0` 重做了对话区：回答放进浅底卡片、工具调用默认折叠，
0.8.0 让「设置 → 修改地址」可以在电脑换隧道地址后只改地址、保留设备授权（不必重新配对）；0.7.0 把打开会话时拉取的历史从「不限」改成 30 条消息（实测快照 312 条记录/529 KB → 177 条/267 KB），
这是「每次加载都很慢」的主要来源；`v0.6.0` 用 `reverseLayout` 彻底修掉「打开会话停在最上面」、补上 Markdown 表格与分隔线渲染、
并让重进会话时先显示上次的渲染结果而不是空白等待）。
诚实说明验证到了什么程度：

**已验证**

- Android 工程编译通过（debug + release），R8 收缩后 11.63 MB
- 41 项单元测试通过：8 项协议解析（用真实抓包帧做夹具）+ 6 项回复语言指令过滤
  + 13 项 Markdown 解析（含表格、分隔线、围栏内表格不被误判）+ 14 项工具行配对与参数摘要
- **22 项与真实电脑的协议一致性测试通过**（配对、列会话、建会话、模型目录与权限档位、
  切换模型、切换权限并回读投影、提交、收流、完成信号、取消、审批回执校验、心跳）。
  这一轮是**钉在可用模型上**跑绿的（第三个参数 `buddy/deepseek-v4.1-flash`）；不钉模型、
  直接用电脑默认模型时会因该线路自身的问题挂两项，原因见上面的错误码表。
- 审批链路端到端验证：waterfall 帧 → 回执 → 电脑落 `approval/decided`
- 回复语言指令端到端验证：附加「请用简体中文回答」后，真实电脑的回答里
  繁体专用字集合为空（`traditional-only chars found = []`）
- 模型与权限的读写路径在真实电脑上验证：`session/modelCatalog` 返回 9 个分组、
  127 个模型；`permissionPresets/catalog` 返回三个预设；`session/selectModel` 与
  `commands/execute` 的 `/permission <preset>` 都成功改动了状态，并用
  `session/projections` 读回确认（见 `docs/sources.md` 第 2 节）
- 选择模型只影响该会话，**不会**改掉电脑的全局默认模型（实测：切走再读
  `session/modelCatalog` 的 `default` 不变）

**未验证**

- **未在物理手机上安装运行。** 开发环境没有连接 Android 设备（`adb devices` 为空），
  UI 的实际渲染与触摸交互没有真机确认。0.2.0 的界面改版、0.3.0 的两个选择器、
  0.4.0/0.4.1 的扫码与工具行、0.5.0/0.6.0 的对话区重做，都因此只有编译期与单元测试
  层面的保证：弹层高度、深色主题下的对比度、小屏上胶囊是否会挤压输入框、相机在真机上
  的对焦与权限弹窗、`reverseLayout` 下表格横向滑动的手感，全都需要你装上看。
- **扫码只在模拟器之外可用。** 构建剔掉了 x86/x86_64 的 ML Kit 原生库以省 12 MB，
  所以 `adb` 模拟器上「扫一扫」会失败；相册选图那条路是纯 ZXing，不受影响。
- **换模型不会自动改推理档位。** 桌面端把档位挂在每个模型自己声明的
  `reasoning.efforts` 上，两个已知模型都声明了 `off/low/high/max`，但线路之间并不一致：
  实测给 `trae/glm-5.1` 发 `high` 会被拒（`session/model-unavailable`）。手机端的做法是
  只列该模型自己声明的档位，不补默认、不猜测；换到不带推理的模型时省略该字段而不是发
  `null`。没有做的是「换模型时自动挑一个合法档位」——那会在用户没看的地方静默改掉设置。
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
