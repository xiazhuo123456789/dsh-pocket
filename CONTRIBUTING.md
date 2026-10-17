# 贡献指南

## 在动手之前

先开 issue 说明你想改什么。这个项目的核心价值是**不撒谎的状态语义**，所以涉及状态
机的改动需要先对齐设计，而不是先写代码。

## 开发环境

见 [docs/android-build.md](docs/android-build.md)。最短路径：

```bash
cd android-app
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## 代码约定

### 分层不能破

```
ui → core → protocol
```

- `protocol/` 是唯一知道 JSON 形状的地方。不要在这里引用 Compose 或 Android UI。
- `core/` 是唯一改变 UI 状态的地方。UI 组件不持有业务状态。
- `ui/` 里不出现网络调用，不出现 `System.currentTimeMillis()` 之外的业务判断。

### 状态只来自 Host 信号

这是硬性约束。任何「先乐观地把界面改成成功，等回执再修正」的写法都会被拒。

具体说：

- 消息的「已发送」必须等到 `prompt()` 返回 `accepted:true`
- 停止的「已确认」必须等到 `turn/end` 事件
- 审批的「已处理」必须等到电脑回执

如果做不到，就显示「处理中」或「未确认」，不要猜。

### 错误必须可见

网络失败不能静默。`catch` 块要么更新 UI 状态，要么重新抛出，不允许空吞。

### 注释写「为什么」

不写「这里调用了 API」，写「这里必须先取消再关闭，否则 Host 会认为是异常断开」。

## 测试

### 单元测试

```bash
cd android-app && ./gradlew :app:testDebugUnitTest
```

协议帧的解析在 `ProtocolTest.kt`。新增帧类型时**必须**加一个用真实帧做的用例。
测试里的 JSON 请从 `tests/frames.json`（由 `tests/dump-events.mjs` 生成）里摘，
不要手写想象出来的结构。

### 一致性测试

```bash
node tests/conformance.mjs http://127.0.0.1:19387 <deviceId>
```

这是真实电脑上的端到端检查。改动协议层后必须跑通。

### 提交前

- [ ] `testDebugUnitTest` 通过
- [ ] `assembleRelease` 通过（R8 会暴露只在 release 出现的问题）
- [ ] `conformance.mjs` 全绿
- [ ] 没有新增未使用的权限
- [ ] 没有把凭据、deviceId、配对链接写进代码或测试

## 提交信息

```
<area>: <what changed>

<why it changed, if not obvious>
```

`area` 用 `protocol` / `ui` / `core` / `docs` / `build`。

## 不接受的改动

- 绕过 `/remote` 门控去直连内部接口
- 把配对凭据或 API Key 存到明文位置
- 让 UI 猜测状态而不是等待 Host 信号
- 引入需要相机/存储/通讯录等额外权限的功能
- 为「目录看起来完整」而创建的空模块

## 发布

1. 更新 `app/build.gradle.kts` 的 `versionCode` / `versionName`
2. `./gradlew :app:assembleRelease`
3. 把 APK 放进 `dist/`，命名 `dsh-pocket-<version>-release.apk`
4. 打 tag `v<version>`，写 release notes
5. 附上 APK

## 许可证

贡献的代码以 MIT 发布。提交 PR 即表示你同意这一点。
