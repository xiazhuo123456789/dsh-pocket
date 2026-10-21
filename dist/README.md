# 构建产物 / Build artifacts

## v0.12.0

| 文件 / File | 大小 / Size | SHA-256 |
| --- | --- | --- |
| `dsh-pocket-0.12.0.apk` | 44.24 MiB (46,389,170 B) | `22ef524b1f219ab61bde916286214e91be005ee2c71aeb410e11532532ed10c1` |

这一版加了**内置桌宠**：16 段动画、3444 帧、0.7× 的透明 WebP（288×241）随 APK 一起安装，
用 Compose 原生渲染。开在「设置 → 桌宠 → 显示桌宠」，可以拖滑块调大小。

- **离线可用**：素材在 APK 里，没配对、断网、电脑关机都能待机和玩耍。
- **不申请权限**：纯应用内绘制，不需要悬浮窗权限，也不会浮到别的应用上。
- **会跟着电脑的状态变**：连上电脑后，思考/工作/等待审批/成功/出错各有对应动画；
  不连时只播 idle 和随机小动作。

体积从 11.64 MiB 涨到 44.24 MiB，多出来的 32.6 MiB 全是美术素材 —— 这不是无用资源，
`shrinkResources` 压不掉它，也不该压掉。

> **素材许可**：动画帧来自 [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)
> （MIT，Copyright (c) 2026 PC2005-cloud），经 `QCYTSN/dsh-dafeiyu`（MIT）转码。
> 版权与许可原文随 APK 打包在 `assets/pet/LICENSE.txt`，仓库侧见
> `THIRD_PARTY_NOTICES.md`。

## v0.10.0

| 文件 / File | 大小 / Size | SHA-256 |
| --- | --- | --- |
| `dsh-pocket-0.10.0.apk` | 11.64 MiB (12,202,047 B) | `698b02640ae4b1879cb6cba1fabd5a2a5aefe74de06d69faf9bea4de0a85ee7f` |
| `dsh-pocket-0.10.0-debug.apk` | 30.67 MiB (32,160,229 B) | `edafced3e96500764aa662a271b6509c1653e0188da4f8149604791bcdd9f9ea` |

`dsh-pocket-0.10.0.apk` 是体积优化后的发布包（R8 代码压缩 + 资源收缩），日常安装用这个。
`dsh-pocket-0.10.0-debug.apk` 保留完整符号信息，只在排障或抓堆栈时用。

The `0.8.0` APK is the shrinker-built release; use it for normal installation. The
`-debug` APK keeps full symbols and exists for troubleshooting only.

两者使用**相同**的调试密钥签名，可以互相覆盖安装，不需要先卸载。
Both are signed with the **same** debug key, so they can overwrite each other without
uninstalling first.

校验下载完整性和签名身份 / verify a download:

```bash
sha256sum dsh-pocket-0.10.0.apk
# → aab94290febd6f92e28fd9768b8d7c98cde375cbfb02d9965fb6f5a43d76d02c

apksigner verify --print-certs dsh-pocket-0.10.0.apk
# → signer #1 certificate SHA-256 digest: ee49dd744f1092f51f31c6e29cbdb90565294f7f5b6c153317d7bdaea029dfaa
```

> **体积说明**：0.3.0 是 1.77 MiB，0.5.0 起是 11.63 MiB。差别全部来自扫码 ——
> CameraX 加上内置的 ML Kit 条码模型（`libbarhopper_v3.so`，每个 ABI 3.3–6.1 MiB）。
> 构建里已经把 `x86` 与 `x86_64` 两份剔掉（见
> `android-app/app/build.gradle.kts` 的 `jniLibs.excludes`），保留 `arm64-v8a` 与
> `armeabi-v7a`。**代价**：模拟器上打开扫码会失败，相册选图那条路不受影响
> （纯 ZXing，无原生库）。实机（arm64）扫码正常。
>
> **Size note**: 0.3.0 was 1.77 MiB, 0.5.0 onwards is 11.63 MiB. The whole difference is
> the QR scanner — CameraX plus the *bundled* ML Kit barcode model. The build drops the
> `x86`/`x86_64` copies of `libbarhopper_v3.so`; `arm64-v8a` and `armeabi-v7a` are kept.
> Camera scanning therefore does not work on emulators, while the "pick a screenshot"
> path still does (pure ZXing, no native library).

## 安装 / Install

```bash
adb install -r dsh-pocket-0.12.0.apk
```

或者把 APK 传到手机上点击安装（需要在系统设置里允许「安装未知来源应用」）。
Or copy the APK to the phone and tap it (the system will ask you to allow installing
from an unknown source).

首次打开会进入配对页。手机和电脑需要在同一网络下，或者电脑已经开了可公网访问的隧道。
The first launch lands on the pairing screen. The phone and the desktop need to share a
network, or the desktop needs to expose a public tunnel.

装好后到「设置 → 关于 → 版本」核对 `0.12.0 (13)`。如果显示 `0.11.0 (12)` 或更早，说明装的是旧包（旧包没有桌宠）。

---

## 历史版本 / Older builds

旧版本的 APK 不上传，只留哈希；需要复现请按 `docs/android-build.md` 自己构建。
Older APKs are not uploaded; only hashes are recorded. Rebuild them per
`docs/android-build.md` if you need to reproduce one.

| 版本 | 发布包大小 | SHA-256 |
| --- | --- | --- |
| 0.7.0 | 12,200,227 B | `1ce24c8f0f006f8cda3bb0bfc80a901f2fe0330d204a904f19698c1b9f91f48d` |
| 0.6.0 | 12,200,227 B | `aab94290febd6f92e28fd9768b8d7c98cde375cbfb02d9965fb6f5a43d76d02c` |
| 0.5.0 | 12,200,227 B | `877175f507d1fd58a1d6eccd9dbf3089fb91c71dbbaa72476f4c26ed6bfc0073` |
| 0.4.1 | 12,200,227 B | `6482e113368b67c19c442ab954d522ea315a71596b36ad1b9996928b769c71c9` |
| 0.4.0 | 12,200,227 B | `4cb30853d8655cce8420820f6e3d47275b02adf95fb50168077bcb566847eb4e` |
| 0.3.0 | 1,856,062 B | `7d354d36beb1cc492636a1678368f417e37b38736694dac59c80da30519da813` |
| 0.2.0 | 1,839,674 B | `40eb5efe6ce29c8e9b596572bab3e428f88bd7c440e4f9175f6d372efbeb46dd` |
