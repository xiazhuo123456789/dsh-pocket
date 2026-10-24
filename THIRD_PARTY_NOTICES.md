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

## 随 APK 分发的美术素材

`android-app/app/src/main/assets/pet/` 下的动画帧来自 dsh-pet 项目的 `assets/webm`。
**该项目的代码是 MIT，但美术素材与角色形象不适用 MIT**，而是
[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)
（署名 · 非商业 · 相同方式共享）：

| 组件 | 版权 / 出处 | 许可证 | 说明 |
| --- | --- | --- | --- |
| dsh-pet 动画素材 | Copyright (c) 2026 PC2005-cloud | CC BY-NC-SA 4.0 | <https://github.com/PC2005-cloud/dsh-pet> |
| 形象原型「溟月」 | B 站 @上善无形 | CC BY-NC-SA 4.0 | <https://space.bilibili.com/4456176> |
| DeepSeek 版（女仆鲸鱼娘）设计 | B 站 @ZipZipPipe | CC BY-NC-SA 4.0 | <https://space.bilibili.com/4168597> |

形象是社区二创的 DeepSeek「大肥鱼」（鲸鱼娘），**非 DeepSeek 官方形象**。原作者声明原文：
「大肥鱼是遵守CC BY-NC-SA 4.0协议的形象，不可以以任何形式商业化。」

因此本应用**仅限非商业使用**：不得以任何形式售卖、内购或以营利为目的分发。若对素材做了
改编，改编后的素材须以同样的 CC BY-NC-SA 4.0 发布。

这批帧直接由上游原生 24 fps 的透明 VP9 WebM（`assets/webm`，106 段）解出：
本项目用 `ffmpeg -c:v libvpx-vp9` 取出透明通道、按 `fps=12` 与 `scale=200` 抽帧，
再以 WebP quality 60 重新编码，并按「每段一个 `.bin` + `pet-manifest.json` 记录每帧
字节长度」打包。完整的版权与许可原文随素材一起打包在 `assets/pet/LICENSE.txt`。

角色形象由上游项目以 AI 生成。缩放、重编码与重新打包只改变文件格式，不改变美术素材本身的
权利归属；不提供任何担保。dsh-pet 名称及其项目标识归各自所有者。

## 关于协议

本仓库的 `docs/protocol.md` 与 `tests/` 中的脚本描述并调用 DeepSeek Harness 的公开
线协议。这些描述来自对实际通信的观察，用于互操作性目的。
