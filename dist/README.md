# 构建产物 / Build artifacts

## v0.3.0

| 文件 / File | 大小 / Size | SHA-256 |
| --- | --- | --- |
| `dsh-pocket-0.3.0.apk` | 1.77 MiB (1,856,062 B) | `57ede41eee425e427e8d01035c8f11228a217fc4500b522f7730a4e462134e1d` |
| `dsh-pocket-0.3.0-debug.apk` | 18.85 MiB (19,764,899 B) | `1b9dd1b53e0a016de7367e7b060174798fe314711c9bf888c3f2701ce7ceab1b` |

`dsh-pocket-0.3.0.apk` 是体积优化后的发布包（R8 代码压缩 + 资源收缩），日常安装用这个。
`dsh-pocket-0.3.0-debug.apk` 保留完整符号信息，只在排障或抓堆栈时用。

The `0.3.0` APK is the shrinker-built release; use it for normal installation. The
`-debug` APK keeps full symbols and exists for troubleshooting only.

两者使用**相同**的调试密钥签名，可以互相覆盖安装，不需要先卸载。
Both are signed with the **same** debug key, so they can overwrite each other without
uninstalling first.

校验下载完整性和签名身份 / verify a download:

```bash
sha256sum dsh-pocket-0.3.0.apk
# → 57ede41eee425e427e8d01035c8f11228a217fc4500b522f7730a4e462134e1d

apksigner verify --print-certs dsh-pocket-0.3.0.apk
# → signer #1 certificate SHA-256 digest: ee49dd744f1092f51f31c6e29cbdb90565294f7f5b6c153317d7bdaea029dfaa
```

## 安装 / Install

```bash
adb install -r dsh-pocket-0.3.0.apk
```

或者把 APK 传到手机上点击安装（需要在系统设置里允许「安装未知来源应用」）。
Or copy the APK to the phone and tap it (the system will ask you to allow installing
from an unknown source).

首次打开会进入配对页。手机和电脑需要在同一网络下，或者电脑已经开了可公网访问的隧道
地址——具体要求见 [../docs/feasibility.md](../docs/feasibility.md)。

The first launch lands on the pairing screen. Phone and desktop must be reachable from
each other — same LAN, or a publicly reachable tunnel origin on the desktop side. See
[../docs/feasibility.md](../docs/feasibility.md).

## 版本信息 / Version

```
package:           dev.dshpocket
versionCode:       3
versionName:       0.3.0
minSdkVersion:     26 (Android 8.0)
targetSdkVersion:  35 (Android 15)
compileSdkVersion: 35
```

在应用内的「设置 → 关于 → 版本」里也能看到 `0.3.0 (3)`。如果你看到的是
`0.2.0 (2)` 或更早，那装的是旧包，下面这一版的新行为都不会出现。

The in-app value is under Settings → About → Version. If it reads `0.2.0 (2)` or lower,
the installed package is an older build and none of the 0.3.0 behaviour applies.

## 申请了哪些权限 / Declared permissions

| 权限 / Permission | 用途 / Why |
| --- | --- |
| `INTERNET` | 与电脑通信 / talk to the desktop |
| `ACCESS_NETWORK_STATE` | 判断在线离线，用于连接横幅 / online-offline for the connection banner |
| `POST_NOTIFICATIONS` | 任务完成、需要审批、连接断开 / task finished, approval needed, link lost |
| `FOREGROUND_SERVICE` | 有任务在跑时保活 / stay alive while a turn is in flight |
| `FOREGROUND_SERVICE_DATA_SYNC` | 同上，Android 14+ 必需 / same, required on Android 14+ |
| `WAKE_LOCK` | 前台服务期间的短时唤醒 / brief wake lock during the foreground service |

**没有**申请相机、存储、通讯录、位置、电话、短信。扫码走系统图片选择器（photo picker），
只读用户主动选中的那一张图，不申请任何媒体权限。

**Not** requested: camera, storage, contacts, location, phone, SMS. QR scanning goes
through the system photo picker, which reads only the single image the user selects and
grants no media permission.

## 关于签名 / About the signature

当前使用 Android **调试密钥**签名，方便直接安装体验。这个密钥在全世界每一台装了
Android SDK 的机器上都一样（公开的），所以**不适合对外分发**——任何人都能签一个同名
同包的 APK 覆盖安装，且不会被系统拦下。

This build is signed with the **Android debug key** so it can be installed and tried
immediately. That key is identical on every machine with the Android SDK, so it is
**not suitable for distribution**: anyone can sign an APK with the same package name and
overwrite the installed app, and the system will allow it.

要发布自己的版本，请换成自己的密钥，步骤见
[../docs/android-build.md](../docs/android-build.md)。

To publish your own build, use your own key — see
[../docs/android-build.md](../docs/android-build.md).

## 自行构建 / Build it yourself

```bash
cd ../android-app
./gradlew :app:assembleRelease
```

产物在 `app/build/outputs/apk/release/`。构建需要 JDK 17 和 Android SDK 35，完整环境
搭建步骤见 [../docs/android-build.md](../docs/android-build.md)。

Output lands in `app/build/outputs/apk/release/`. Building needs JDK 17 and Android SDK
35; the full setup walkthrough is in [../docs/android-build.md](../docs/android-build.md).
