# 构建产物

## v0.1.0

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| `dsh-pocket-0.1.0-release.apk` | 1.72 MB | `cffa1faeb8b3ba2509119dd89d7b41b645dba53a4d6f0aeb37366090df3d1181` |
| `dsh-pocket-0.1.0-debug.apk` | 18.72 MB | `6e624ce549bc63dced21c35cb89cc7e2d4c292a291e1e6ef5bf7eaa78b659d73` |

`release` 是体积优化后的发布包（R8 压缩 + 资源收缩），`debug` 保留完整符号便于排障。
两者使用**相同的调试密钥**签名，可以互相覆盖安装。

## 安装

```bash
adb install dsh-pocket-0.1.0-release.apk
```

或者把 APK 传到手机上点击安装（需在系统设置里允许「安装未知来源应用」）。

## 版本信息

```
package:        dev.dshpocket
versionCode:    1
versionName:    0.1.0
minSdkVersion:  26 (Android 8.0)
targetSdkVersion: 35 (Android 15)
compileSdkVersion: 35
签名:            Android Debug（SHA-256 ee49dd744f1092f51f31c6e29cbdb90565294f7f5b6c153317d7bdaea029dfaa）
```

## 申请了哪些权限

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 与电脑通信 |
| `ACCESS_NETWORK_STATE` | 判断在线/离线 |
| `POST_NOTIFICATIONS` | 任务完成、需审批、断连通知 |
| `FOREGROUND_SERVICE` | 任务运行时保活 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 同上，Android 14+ 必需 |
| `WAKE_LOCK` | 前台服务期间保持唤醒 |

**没有**申请：相机、存储、通讯录、位置、电话、短信。扫码走系统图片选择器，只读用户
主动选的那一张图。

## 关于签名

当前使用 Android 调试密钥签名，方便直接安装体验。这个密钥是公开的，**不适合对外分发**——
任何人都能签一个同名同包的 APK 覆盖安装。发布公开版本前请换成自己的密钥，见
[../docs/android-build.md](../docs/android-build.md)。

## 自行构建

```bash
cd ../android-app
./gradlew :app:assembleRelease
```

产物在 `app/build/outputs/apk/release/`。
