# 排障

## 配对

### 「无法识别配对链接，请整段粘贴电脑上显示的链接」

应用需要形如 `https://<host>/pair-accept?pair=<token>` 的完整 URL。

- 复制时是否漏了 `?pair=` 之后的部分？
- 链接是否已过期？配对令牌一次性且有有效期，重新生成一个。
- 是否把 `https://` 一起复制了？应用需要完整 URL，不接受裸主机名。

### 二维码识别不出来

把电脑上的二维码**原图**发到手机，不要在聊天软件里二次压缩或截取局部。应用会先把
图片缩到 1600px 长边再解码，太小的截图会失败。

识别失败时应用会提示改用手动粘贴，链接和二维码内容是一样的。

### 配对成功但马上断线

最常见的原因：**电脑端隧道地址变了**。Cloudflare quick tunnel 的主机名在每次
DeepSeek Harness 启动时重新分配，旧链接随之失效。

处理：回电脑上看远程控制面板的当前地址，重新生成配对链接，在手机上重新配对。

## 连接

### 所有请求都返回 403

```
{"code":"unpaired","message":"this device is not paired with the desktop"}
```

设备凭据已失效。可能是：

- 在电脑端撤销了这台设备
- 电脑端清空了配对状态
- 手机端「断开并清除」之后又在旧连接上发请求

处理：重新配对。手机端设置里的「断开并清除」会清掉本机凭据，之后可以重新粘贴新链接。

### 能连上但会话列表是空的

电脑上确实没有会话，或者这些会话属于另一个 profile。DSH Pocket 只看到当前运行实例
的会话。

点右上角刷新。如果仍然为空，去电脑上确认会话存在。

### 状态一直停在「已发送停止请求」

这是**故意**的。停止请求返回 `{accepted:true}` 只说明电脑收到了请求，不代表任务停了。
应用会等该会话的 `turn/end` 事件；15 秒内没等到，就显示：

> 停止请求未被确认，任务可能仍在运行

这时去电脑上确认任务状态。这个设计是为了满足「不以界面显示停止为已停止」的要求。

### 状态不更新

`$events` 或 `session/follow` 流断了。应用会在设置里显示连接状态。

- 点设置里的「重新连接」
- 或者退出重进应用（会自动重连并重新 follow 最近会话）

## 任务

### 任务发出去了，但一点回复都没有，状态显示「失败」

先看错误条里的原文。应用会把电脑报告的 `turn/end.reason.error.message` 逐字显示出来，
那句英文通常已经说明了原因。最常见的一种：

```
provider "…" model "…" does not support reasoning effort "high"
（错误码 UNSUPPORTED_REASONING_EFFORT）
```

这不是手机的问题，也不是协议的问题。桌面端的模型配置要求了一个该模型不支持的推理强度，
于是在**发出任何网络请求之前**就结束了这一轮。修法在电脑上：打开
`~/.dsh/profiles/desktop/cordis.patch.yml`，把 `agent-default-model` 段的
`reasoningEffort` 去掉，或者换成该模型声明支持的档位（`off` / `low` / `high` / `max`），
然后在 DeepSeek Harness 里重新加载配置。

判断依据很简单：如果错误文本来自电脑（带错误码），改电脑；如果应用显示的是
「无法连接」这类自身文案，那是链路问题，看上一节。

### `conformance.mjs` 有两项失败，其他都过

那两项是「收到 assistant 消息」和「这一轮没有在电脑端失败」。它们同时失败、且错误码为
`UNSUPPORTED_REASONING_EFFORT` 时，就是上面那个原因——改电脑配置后重跑即可。

这两项是**故意**加进去的：它们把「契约坏了」和「电脑自己拒了这一轮」区分开，因为两者
表现完全相同（都没有 assistant 消息），但修法相反。

## 审批

### 通知上的审批按钮点了没反应

审批必须在 `$events` 的 socket 存活时回答，因为回执需要该连接的 `clientId`。如果应用
进程已被系统杀掉，通知上的按钮会提示「审批已失效」。

处理：打开应用，等它重新连上，审批卡片会重新出现（如果电脑仍在等待）。

### 审批卡片一直不消失

电脑可能已经超时关闭了这个请求。卡片会在下一次收到 `approval/decided` 时移除；如果
一直不动，退出会话再进入。

### 为什么只有「允许一次」和「拒绝」

这是 DeepSeek Harness 的契约：审批结果只有 `allowed-once` 和 `rejected` 两个值，没有
「始终允许」，也不记忆规则。会话级的策略（`ask` / `never`）在电脑上配置。

## 通知

### 收不到通知

- Android 13+ 需要 `POST_NOTIFICATIONS` 权限，首次启动时会请求
- 检查设置里的三个通知开关
- OPPO / ColorOS 需要在系统设置里允许 DSH Pocket 的后台运行与通知，否则系统会吞掉
  后台通知

### 任务跑着跑着应用被杀了

应用在前台服务里保活（`foregroundServiceType=dataSync`），但厂商 ROM 仍可能清理。
在系统设置里把 DSH Pocket 加入「电池优化白名单」。

## 构建

见 [android-build.md](android-build.md) 的「Windows 上的坑」一节。

## 协议调试

仓库里的 `tests/` 目录是排查协议问题的第一工具：

```bash
# 完整闭环，逐项 PASS/FAIL
node tests/conformance.mjs http://127.0.0.1:19387 <deviceId>

# 只 dump 一个会话的原始帧，看看电脑到底发了什么
node tests/dump-events.mjs http://127.0.0.1:19387 <deviceId> "说个 PONG"

# 专门验证审批链路
node tests/poc-approval.mjs http://127.0.0.1:19387 <deviceId> "用 pwsh 执行 echo hello"
```

`dump-events.mjs` 会把帧写到 `tests/frames.json`。

要拿 `deviceId`，在电脑上 `POST /api/pair/issue` 再 `POST /api/pair/accept` 即可。

## 仍然卡住

提 issue 时请附上：

- 应用版本（设置页底部）和 DeepSeek Harness 版本
- 连接方式是隧道还是局域网
- 应用显示的具体错误文本
- 如果是协议问题，附上 `tests/dump-events.mjs` 的输出

**不要在 issue 里贴配对链接、设备 ID 或任何凭据。**
