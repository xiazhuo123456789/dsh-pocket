# 构建 Android 应用

## 前置

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17 | AGP 8.7 要求 |
| Android SDK platform | 35 | `compileSdk` / `targetSdk` |
| Android build-tools | 35.0.0 | |
| Gradle | 8.11.1 | 由 wrapper 提供，无需预装 |

环境变量：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
```

## 构建

```bash
cd android-app
./gradlew :app:assembleDebug     # 可安装的调试包
./gradlew :app:assembleRelease   # 体积优化后的发布包
./gradlew :app:testDebugUnitTest # 协议解析测试
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 签名

`app/build.gradle.kts` 里 release 变体暂时使用调试密钥签名，以便直接安装体验。
发布到商店或公开分发前请替换：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("keystore.jks")
        storePassword = System.getenv("DSH_POCKET_STORE_PASSWORD")
        keyAlias = System.getenv("DSH_POCKET_KEY_ALIAS")
        keyPassword = System.getenv("DSH_POCKET_KEY_PASSWORD")
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        // ...
    }
}
```

不要把 keystore 或密码提交到仓库。

## Windows 上的坑

以下都是实际踩过的，按遇到概率排序。

### 1. 项目路径含中文

AGP 会拒绝含非 ASCII 字符的项目路径：

```
Your project path contains non-ASCII characters. This will most likely cause the build
to fail on Windows. ... This warning can be disabled by adding the line
'android.overridePathCheck=true' to gradle.properties file in the project directory.
```

两个办法，建议都做：

```properties
# gradle.properties
android.overridePathCheck=true
```

```cmd
:: 建一个 ASCII 路径的 junction 指向真实目录
mklink /J C:\dshpocket D:\你的中文路径\dsh-pocket
```

然后从 junction 路径构建。注意 Gradle 的**测试 worker** 比编译更挑路径——编译可能
通过而 `testDebugUnitTest` 失败在：

```
java.lang.ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain
java.io.IOException: 管道正在被关闭
```

这时把 `GRADLE_USER_HOME` 和 `TMP`/`TEMP` 也指向 ASCII 路径即可。

### 2. 内存不足导致 JVM 崩溃

低配机器上 `gradle wrapper` 可能直接崩：

```
# There is insufficient memory for the Java Runtime Environment to continue.
# Native memory allocation (malloc) failed to allocate 2185232 bytes. Error detail: Chunk::new
#  Out of Memory Error (arena.cpp:191)
```

`gradle.properties` 里压住：

```properties
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=96m -XX:+UseSerialGC -Xss1m -Dfile.encoding=UTF-8
kotlin.daemon.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=320m -XX:+UseSerialGC
org.gradle.parallel=false
org.gradle.workers.max=2
org.gradle.daemon=false
```

配合 `--no-daemon --max-workers=1`。

### 3. 下载慢

`services.gradle.org` 和 `dl.google.com` 在国内可能极慢。`settings.gradle.kts` 已经
配置了阿里云镜像。Gradle 分发包本身在 `gradle/wrapper/gradle-wrapper.properties`：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip
```

重新生成 wrapper 时显式指定镜像，否则它会去官方地址校验并超时：

```bash
gradle wrapper --gradle-version 8.11.1 --distribution-type bin \
  --gradle-distribution-url "https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip"
```

大文件用 `curl -L --fail --retry 3` 比 `Invoke-WebRequest` 稳。

### 4. adb 起不来

```
cannot open C:\Users\<you>\AppData\Local\Temp\adb.log: Permission denied
```

把 `TMP` / `TEMP` / `TMPDIR` 指到一个可写目录即可：

```powershell
$env:TMP="E:\dshtmp"; $env:TEMP=$env:TMP
```

### 5. R8 报告缺失注解类

```
ERROR: R8: Missing class com.google.errorprone.annotations.CanIgnoreReturnValue
```

`androidx.security:security-crypto` 传递依赖 tink，tink 引用了仅在编译期存在的
errorprone 注解。`proguard-rules.pro` 已包含：

```proguard
-dontwarn com.google.errorprone.annotations.**
```

## 依赖说明

| 依赖 | 用途 |
| --- | --- |
| Jetpack Compose (BOM 2024.10.01) | UI |
| navigation-compose | 屏幕导航 |
| okhttp | HTTP + WebSocket |
| kotlinx-serialization-json | JSON（lenient，容忍未知字段） |
| security-crypto | 凭据加密存储 |
| datastore-preferences | 设置 |
| zxing core | 二维码解码（仅解码本地图片，不引入相机） |
| work-runtime-ktx | 后台约束 |

## 没有用到的权限

应用**不申请**相机权限。扫码通过系统图片选择器（`PickVisualMedia`）完成，只在用户
主动选图时读取那一张图。
