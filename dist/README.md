# 构建产物 / Build artifacts

## v0.9.0

| 文件 / File | 大小 / Size | SHA-256 |
| --- | --- | --- |
| `dsh-pocket-0.9.0.apk` | 11.64 MiB (12,200,227 B) | `2b2efa83005c73a80005b0e4ba5379fcfa7f0408d044e3c8152470272173c6b7` |
| `dsh-pocket-0.9.0-debug.apk` | 30.85 MiB (32,345,376 B) | `da885950a42f308b23ac280582da18694722f3a417da72a935e990ca36d47dab` |

`dsh-pocket-0.9.0.apk` 是体积优化后的发布包（R8 代码压缩 + 资源收缩），日常安装用这个。
`dsh-pocket-0.9.0-debug.apk` 保留完整符号信息，只在排障或抓堆栈时用。

The `0.8.0` APK is the shrinker-built release; use it for normal installation. The
`-debug` APK keeps full symbols and exists for troubleshooting only.

两者使用**相同**的调试密钥签名，可以互相覆盖安装，不需要先卸载。
Both are signed with the **same** debug key, so they can overwrite each other without
uninstalling first.

校验下载完整性和签名身份 / verify a download:

```bash
sha256sum dsh-pocket-0.9.0.apk
# → aab94290febd6f92e28fd9768b8d7c98cde375cbfb02d9965fb6f5a43d76d02c

apksigner verify --print-certs dsh-pocket-0.9.0.apk
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
adb install -r dsh-pocket-0.9.0.apk
```

或者把 APK 传到手机上点击安装（需要在系统设置里允许「安装未知来源应用」）。
Or copy the APK to the phone and tap it (the system will ask you to allow installing
from an unknown source).

首次打开会进入配对页。手机和电脑需要在同一网络下，或者电脑已经开了可公网访问的隧道。
The first launch lands on the pairing screen. The phone and the desktop need to share a
network, or the desktop needs to expose a public tunnel.

装好后到「设置 → 关于 → 版本」核对 `0.9.0 (10)`。如果显示 `0.8.0 (9)` 或更早，说明装的是旧包。

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
