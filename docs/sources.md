# Sources and Attribution / 来源与引用

This file records **where every external fact in this repository came from**. It exists
for two reasons: so a reader can verify our claims, and so we never present someone
else's work or wording as our own.

本文件记录仓库中**每一条外部事实的来源**。目的有两个：让读者可以核实我们的结论；以及确保
不把别人的工作或措辞当成自己的。

---

## 1. Licensing position / 许可立场

DSH Pocket is an **independent, unaffiliated** project. It is not produced, endorsed,
or sponsored by DeepSeek.

DSH Pocket 是一个**独立、无隶属关系**的项目，不由 DeepSeek 制作、背书或赞助。

| Component | Relationship | License |
|---|---|---|
| This repository's own code | Written for this project | MIT (see [`LICENSE`](../LICENSE)) |
| DeepSeek Harness (desktop) | **Not vendored.** A separate program the user installs and runs. We talk to its HTTP/WebSocket surface. | MIT |
| `@linxin666/dsh-remote-web-ui` | **Not vendored.** A third-party plugin the user installs into their own Harness profile. We call the pairing endpoints it exposes. | Apache-2.0 |
| AndroidX / Jetpack Compose / OkHttp / kotlinx.serialization / ZXing | Normal Maven dependencies, not copied source | Apache-2.0 / MIT |

No third-party source code has been copied into this repository. Every `.kt` file here
was written for DSH Pocket. The only exception is ordinary API usage of the libraries
listed above, which is what those libraries exist for.

**本仓库没有拷贝任何第三方源代码。**所有 `.kt` 文件均为本项目编写；对上述库的使用属于
正常的 API 调用。

See [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) for the full dependency list
and license texts pointers.

---

## 2. Primary sources: the shipped software on the author's machine / 一手来源

The protocol facts in this project were **not** taken from blog posts or from another
app's source. They were read out of the actual software installed on the development
machine, then confirmed against the running process. Each fact below names the artifact
it came from.

本项目的协议事实**不是**来自博客或他人的应用源码，而是从开发机上真实安装的软件中读出，
再用运行中的进程验证。

| What we learned | Where it came from | How it was confirmed |
|---|---|---|
| The desktop exposes a typed RPC surface over `/api` and `/api/remote.mux` | `@deepseek-ai/dsh-api-gateway` package README (`README.zh.md`) shipped inside the desktop app bundle | Called the endpoints against the running desktop |
| `session/*`, `skills/*`, `fileReferences/*` method shapes and wire argument names | Generated client `lib/typert.remote-client.js` inside `@deepseek-ai/dsh-api-session-controller` | The gateway rejects a wrong argument name with `gateway/arguments-invalid`, which pinned each name |
| The exact `session/list` result schema — every field name on a list item (`agentAvailable`, `sessionId`, `updatedAt`, `running`, `blank`, `parentSessionId`, `origin`, `projections`) | The controller's own generated result schema (`_deepseek_ai_dsh_api_session_controller_session_list_result$schema`) inside `resources/app.asar` of the desktop install | Field names read from the schema expression, then cross-checked against a live `session/list` response |
| That `agentAvailable` and `blank` are **two independent fields**, so neither may be derived from the other | The same schema, plus the controller's own comment that the `blank` hint is reconciled against the `sessionListMetadata` projection because it can go stale | Read in the shipped bundle; `tests/conformance.mjs` asserts the list shape stays parseable |
| Mux frame types (`open`/`item`/`end`/`cancel`, `error`) | `@deepseek-ai/dsh-api-gateway` implementation | Round-tripped real frames (see `tests/dump-events.mjs`) |
| `$events` opening frame `{type:"ready", clientId, host:{home}}` | Live WebSocket capture | Captured verbatim in `tests/dump-events.mjs` output |
| Approval is a waterfall event answered through `$events/result` | `@deepseek-ai/dsh-user-approval` and `@deepseek-ai/dsh-api-gateway` implementation | End-to-end approval round trip (`tests/poc-approval.mjs`) |
| Only `allowed-once` and `rejected` are valid approval outcomes | `OUTCOMES` constant in `@deepseek-ai/dsh-user-approval` | Confirmed by an accepted and a rejected reply |
| Pairing lives at `/api/pair/*` and is unreachable over `/remote` | `@linxin666/dsh-remote-web-ui` `src/routes.ts` and `src/remote-methods.ts` | `GET /remote/api/pair/status` returns `403 forbidden` |
| The device credential is a header or cookie named per the plugin config | `src/pairing.ts`, `src/remote-api.ts` in the same plugin | Pairing flow issued a credential that worked as `x-dsh-remote-device` |
| Event types such as `turn/end`, `assistant/message`, `approval/asked` | Live `session/follow` capture | Recorded in `tests/dump-events.mjs` |
| The full `turn/end` reason vocabulary — `completed`, `max-tokens`, `aborted`, `interrupted`, `blocked`, `error` (plus a seed-only `forked` that never ends a live turn) | `turnEndToStopReason` in `@deepseek-ai/dsh-acp/codec`, inside `resources/app.asar` | Mapped exhaustively in `PocketViewModel.handleEvent`; a live session ended with `interrupted` |
| `reasoningEffort` accepts only `off`/`low`/`high`/`max`, and an unsupported value fails with `UNSUPPORTED_REASONING_EFFORT` **before** any network I/O | `@deepseek-ai/dsh-llm` selection validation, inside `resources/app.asar` | Reproduced live: a `turn/end` carried exactly that message and code |
| `session/modelCatalog` takes **no arguments** and returns `{groups:[{id,name,models:[{id,name,description?,reasoning?:{efforts:[{id,name,description?}],defaultEffort}}]}],default:{provider,model,reasoningEffort},failures:[...]}`; **23 of the 127 models on this desktop carry no `reasoning` field at all** | Live `session/modelCatalog` call on the running desktop; argument arity cross-checked against `lib/typert.remote-client.js` (`parameters: []`) | Group and model counts, an `efforts` array, and the absent-`reasoning` case were all observed in the response, not assumed |
| `permissionPresets/catalog` takes no arguments and returns `{options:[{value,name}],defaultOptions:[...],defaultPreset}`, with exactly `read-only` / `workspace-write` / `danger-full-access` present | Live call; the three values also appear as `permission/preset` events in captured session journals | Values copied from the response; the picker renders whatever the desktop sends and only hard-codes which value is `danger-full-access` |
| `session/projections` takes `{"request":{"sessionId"}}` and its `values` block carries `modelSelection.{lastUsed,next}` and `permissions.currentValue` | Live call. The `_request` spelling, a bare `sessionId`, and an `address` object each failed with `gateway/arguments-invalid` or `gateway/input-invalid` | All three wrong shapes were tried and their exact rejection recorded before the right one was adopted |
| There is **no** `session/setPermission` endpoint; the preset is changed through `commands/execute` with `line: "/permission <preset>"`, and a `success` result only means the command ran | Live probe: `/permission` through `commands/execute` answered `{"result":{"kind":"success","text":"preset read-only"}}` while the projection's `currentValue` was the only thing that proved the change | Confirmed by re-reading `session/projections` afterwards; this is why the phone never treats the command result as the confirmation |
| `session/selectModel` answers with `selected` — the triple the desktop actually stored — which can differ from the request | Live call: requested `deepseek-official/deepseek-flash/low` was echoed back exactly, and the projection's `next` moved to it | The returned value is what the UI renders; the request is only a fallback |
| A **fresh session reports `modelSelection.next = null`** and inherits the desktop's global default, which `session/modelCatalog` publishes as `default` | Live `session/projections` on a brand-new session answered `{"lastUsed":null,"next":null}` while the catalog's `default` named a model | This is why the phone's model chip falls back to the catalog default instead of showing "未知"; `tests/conformance.mjs` asserts the premise so a desktop change breaks loudly |
| `session/selectModel` is **per-session**: it does not rewrite the desktop's global default, so future sessions keep inheriting `default` | Probed by selecting a different model on one session, then re-reading `session/modelCatalog`'s `default` — unchanged; restored to be safe | Confirms the phone's picker cannot silently change what the user's next session will use |
| Not every model accepts every `reasoningEffort`; sending one it does not advertise fails at **selection** time with `session/model-unavailable` (e.g. `provider "trae" model "glm-5.1" does not support reasoning effort "high"`) | Live `session/selectModel` calls against `trae/glm-5.1` and `our-free-model/mimo-v2.6-flash-free` | The picker therefore lists only the efforts each model itself declares and omits the field for models without a `reasoning` block |
| The desktop's **global default model can itself be unusable** on a given machine, and the failure surfaces only as a `turn/end` error — observed as `413 preflight_compress_failed` (`context ~218835 tokens exceeds the model window 119808`) on a relay provider, and `MISSING_CREDENTIAL` on a first-party one with no stored key | Live `turn/end` `reason.error` payloads, relayed verbatim by `tests/conformance.mjs` | Not a phone defect: the phone prints the desktop's own message. `conformance.mjs` grew an optional third argument so the turn checks can be pinned to a model that answers, proving the contract independently of the desktop's default |

**These are observations of a specific installed version**, not a specification. They can
change when the desktop or the plugin updates. That is exactly why
[`tests/conformance.mjs`](../tests/conformance.mjs) exists: it re-checks the contract
against a live desktop so a version bump fails loudly instead of silently.

**这些是对特定安装版本的观察，不是规范。**版本升级后可能变化——这正是
`tests/conformance.mjs` 存在的原因。

### Versions observed / 观察到的版本

- DeepSeek Harness desktop shell: `0.2.0-rc.2` (build commit `04f392c9ddd144fa426da2045178797da6db6c11`)
- `@linxin666/dsh-remote-web-ui`: `0.4.5`
- Protocol documented in [`docs/protocol.md`](../docs/protocol.md)

---

## 3. Upstream projects examined during the feasibility review / 可行性审查中考察的上游项目

The design brief for this project named three repositories to examine. All three were
inspected via the public GitHub API and their public pages. **No code was copied from
any of them.** They are credited here because reading them shaped the architecture
decisions documented in [`docs/feasibility.md`](../docs/feasibility.md).

| Repository | License | What we took from it |
|---|---|---|
| [`deepseek-ai/deepseek-harness`](https://github.com/deepseek-ai/deepseek-harness) | MIT | Confirmed the desktop is the execution side and that its plugin/runtime surface is the supported integration point |
| [`april-jk/dsh-mobile-suite`](https://github.com/april-jk/dsh-mobile-suite) | MIT | Its "Android app + relay + agent" split was evaluated and **rejected** for MVP: a relay adds an attack surface without adding capability once the desktop already exposes an authenticated remote surface |
| [`sorsama/deepseek-harness-mobile`](https://github.com/sorsama/deepseek-harness-mobile) | MIT | Prior art for a Kotlin + Compose companion over LAN; informed the decision to keep the phone UI thin and Host-authoritative |

Attribution here is courtesy and honesty about influence, not a license obligation —
no code, comments, or assets were copied.

此处的署名是出于对影响的诚实说明，不是许可义务——没有拷贝任何代码、注释或素材。

### 3.1 随 APK 分发的美术素材 / Artwork bundled in the APK

与上面三处不同，这一条的素材**确实被打包进了 APK**，所以单独列出：

| 来源 | 许可证 | 我们做了什么 |
|---|---|---|
| [`PC2005-cloud/dsh-pet`](https://github.com/PC2005-cloud/dsh-pet)（Copyright (c) 2026 PC2005-cloud） | MIT | 动画形象的原始来源（24 fps 透明 WebM，106 段） |

本项目把上游的 WebM 解成帧：`ffmpeg -c:v libvpx-vp9`（保留 alpha）→ `fps=12,scale=200`
→ RGBA PNG → WebP quality 60，打进 `android-app/app/src/main/assets/pet/`
（106 段 / 12,659 帧 / 38.74 MB）。抽帧与重编码只改变文件格式，不改变素材的权利归属。
MIT 全文随素材打包在 `assets/pet/LICENSE.txt`，`THIRD_PARTY_NOTICES.md` 里有对应条目，
格式说明见 [`docs/pet.md`](../docs/pet.md)。

Character designs were produced upstream with AI assistance; that provenance is upstream's
statement, not something this project verified. No warranty is given for the artwork.

---

## 4. Documentation and specifications referenced / 参考的文档与规范

These are public specifications the implementation follows. They are cited because the
code's behavior is only correct with respect to them.

| Topic | Source |
|---|---|
| JSON-RPC-like request/response envelope used by the gateway | The gateway's own `client-request` / `server-response` shapes, documented in [`docs/protocol.md`](../docs/protocol.md) from observation |
| WebSocket protocol (RFC 6455) | [RFC 6455](https://www.rfc-editor.org/rfc/rfc6455) |
| HTTP semantics used for the one-shot calls | [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110) |
| Android foreground service types | [Android docs: Foreground service types](https://developer.android.com/develop/background-work/services/fg-service-types) |
| Android 13+ notification runtime permission | [Android docs: Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission) |
| Android photo picker (used instead of a camera permission) | [Android docs: Photo picker](https://developer.android.com/training/data-storage/shared/photopicker) |
| Jetpack Compose | [developer.android.com/jetpack/compose](https://developer.android.com/jetpack/compose) |
| OkHttp WebSocket client | [square.github.io/okhttp](https://square.github.io/okhttp/) |
| ZXing barcode decoding | [github.com/zxing/zxing](https://github.com/zxing/zxing) |
| EncryptedSharedPreferences / Jetpack Security | [Android docs: Work with data more securely](https://developer.android.com/privacy-and-security/security-tips) |

---

## 5. What is *our* conclusion, not a sourced fact / 属于本项目判断而非引用事实的部分

To be explicit about the boundary between observation and opinion:

**Observed (can be re-verified against the software):** frame shapes, method names,
argument names, error codes, the approval outcome vocabulary, the `403` on the pairing
surface over `/remote`.

**Our judgement (a design decision, not a fact):** that a separate Windows agent is
unnecessary; that a relay is the wrong default; that the phone should show "unconfirmed"
rather than "stopped" when the desktop does not answer; that the session list should not
clear on disconnect. These are argued in [`docs/feasibility.md`](../docs/feasibility.md)
and [`docs/architecture.md`](../docs/architecture.md), and a reader is free to disagree.

**观察所得**（可对着软件复验）与**本项目的判断**（设计决策）在上表中分开列出，避免把
观点伪装成事实。

---

## 6. Corrections / 更正

If you believe a claim in this repository misattributes a source, copies code it should
not, or states a fact incorrectly, please open an issue. Corrections to attribution are
treated as bugs, not as suggestions.

如果你认为本仓库错误标注了来源、拷贝了不应拷贝的代码，或陈述有误，请开 issue。**来源标注
错误按 bug 处理，不当作建议。**
