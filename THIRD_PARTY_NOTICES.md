# 第三方组件

DSH Pocket 本身以 MIT 许可证发布。它依赖以下第三方组件，各自保留原许可证。

## Android 运行时依赖

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| AndroidX Core KTX | 1.13.1 | Apache-2.0 |
| AndroidX Activity Compose | 1.9.3 | Apache-2.0 |
| AndroidX Lifecycle (runtime-ktx, viewmodel-compose, runtime-compose, process) | 2.8.7 | Apache-2.0 |
| Jetpack Compose BOM / Material 3 / Icons Extended | 2024.10.01 | Apache-2.0 |
| AndroidX Navigation Compose | 2.8.4 | Apache-2.0 |
| AndroidX DataStore Preferences | 1.1.1 | Apache-2.0 |
| AndroidX Security Crypto | 1.1.0-alpha06 | Apache-2.0 |
| AndroidX WorkManager (runtime-ktx) | 2.9.1 | Apache-2.0 |
| Kotlin Coroutines (android) | 1.9.0 | Apache-2.0 |
| KotlinX Serialization JSON | 1.7.3 | Apache-2.0 |
| OkHttp | 4.12.0 | Apache-2.0 |
| ZXing Core | 3.5.3 | Apache-2.0 |

## 构建期依赖

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| Android Gradle Plugin | 8.7.3 | Apache-2.0 |
| Kotlin Gradle Plugin | 2.0.21 | Apache-2.0 |
| Gradle | 8.11.1 | Apache-2.0 |

## 测试工具

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 |
| kotlinx-coroutines-test | 1.9.0 | Apache-2.0 |
| AndroidX Test JUnit / Espresso / Compose UI Test | 1.2.1 / 3.6.1 / BOM | Apache-2.0 |

## 运行环境依赖（不随本仓库分发）

DSH Pocket 与以下软件通信，但不包含、不重分发它们：

| 组件 | 说明 |
| --- | --- |
| DeepSeek Harness Desktop | 远程执行端，由其自身许可证约束 |
| `@linxin666/dsh-remote-web-ui` | 提供配对与远程控制面，Apache-2.0 |
| Cloudflare Tunnel (`cloudflared`) | 公网隧道，由 Cloudflare 条款约束 |

## 关于协议

本仓库的 `docs/protocol.md` 与 `tests/` 中的脚本描述并调用 DeepSeek Harness 的公开
线协议。这些描述来自对实际通信的观察，用于互操作性目的。
