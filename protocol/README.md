# 协议示例

这个目录存放**真实抓包**得到的帧样本，用于文档和测试对照。

## 文件

- `frames.json` — 由 `tests/dump-events.mjs` 生成的原始帧序列

## 生成方式

```bash
node tests/dump-events.mjs http://127.0.0.1:19387 <deviceId> "说个 PONG"
```

会在 `tests/frames.json` 写出该会话从 follow 开始收到的全部帧。用它来：

- 确认某个字段的真实名字（不要猜）
- 新增事件类型时，从里面摘一段放进 `ProtocolTest.kt`

## 注意

`frames.json` 里可能含有会话内容、路径、标题等**本地信息**。提交到公开仓库前请检查
并删掉敏感部分。

协议本身的描述见 [../docs/protocol.md](../docs/protocol.md)。
