plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.dshpocket"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.dshpocket"
        minSdk = 26
        targetSdk = 35
        // 每次发布都要前进，绝不能原地重发同一个 versionCode：Android 允许
        // 用相同 versionCode 覆盖安装，于是"我装过新版了吗"没有任何可核对的依据。
        // 之前连续几轮修复都停在 versionCode=1，用户看到的界面其实还是旧包，
        // 而我们的日志里每一次都是"已修复并发布" —— 这个版本号就是为了让这种
        // 情况不可能再发生。
        //
        // 0.4.1 是 0.4.0 的补丁：0.4.0 里相机绑定失败被一句光秃秃的
        // `runCatching {}` 吞掉，没摄像头的设备（manifest 允许安装）只会看到
        // 一片黑，什么都不会再发生。修法是把三类失败分开讲清楚并给退路。
        //
        // 0.5.0 重做了对话区：助手回答放进浅底卡片（不再是裸文字压在背景上），
        // 工具调用默认折叠（用户原话："那些过程不需要给我看"），打开会话时用重试
        // 循环而非两次 scrollToItem 确保停在最底部（旧版长历史会停在中间）。
        //
        // 0.6.0：
        //   * 列表改 `reverseLayout = true` —— 「打开就停在最新一条」从运行时追赶
        //     变成布局性质，长历史也不会停在中间（前两版的重试循环仍会失败）。
        //   * 补 Markdown 表格与分隔线渲染。0.5.0 把表格当普通段落打印，屏幕上
        //     就是一串竖线，这是用户说「乱七八糟」的直接来源。
        //   * 打开会话先显示上次的渲染结果，不再每次空白几秒。
        versionCode = 12
        versionName = "0.11.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/previous-compilation-data.bin",
        )

        // 只保留 ARM 的 ML Kit 原生库（libbarhopper_v3.so，每个 ABI 约 3.3–6.1 MB）。
        //
        // 加扫码之前 release APK 是 1.8 MB；把 CameraX + 内置 ML Kit 模型加进来之后
        // 变成 24.2 MB —— 因为那一个 .so 给四种 ABI 各打了一份。这个 app 的实机目标
        // 就是手机（minSdk 26，2026 年的手机全是 arm64-v8a），x86 / x86_64 那两份唯一
        // 的用途是在模拟器上跑。为它们付 12 MB 下载量、以及首屏装包时间，不划算。
        //
        // 代价说清楚：**模拟器上扫码功能会不可用**（相册选图仍然可用，那条路纯 ZXing）。
        // 因此这里只过滤 `lib/*/libbarhopper_v3.so`，不动其它任何 .so —— 万一将来有
        // 别的依赖需要 x86，也不会被这条规则误伤。
        jniLibs {
            excludes += setOf(
                "lib/x86/libbarhopper_v3.so",
                "lib/x86_64/libbarhopper_v3.so",
            )
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.google.zxing:core:3.5.3")

    // CameraX + ML Kit barcode scanning, for the "scan the QR with the camera"
    // path. Two notes on why these specific artifacts:
    //
    //  * `camera-camera2` is the real camera implementation; `camera-lifecycle`
    //    ties the session to the composable's lifecycle so the lens is released
    //    when the pairing screen goes away. Declaring only `camera-core` is a
    //    common mistake -- it provides the API but no backend, so preview stays
    //    black.
    //  * `barcode-scanning` is the *bundled* ML Kit model (~2.5 MB, no Play
    //    Services dependency, works offline and on devices without GMS). The
    //    unbundled variant (`com.google.android.gms:play-services-mlkit-barcode-scanning`)
    //    is smaller but downloads its model on first use, which fails exactly
    //    when the user is standing at a computer with no network yet -- the
    //    moment this feature is used.
    //
    // This is the ONLY reason the app asks for the camera, and the permission is
    // requested lazily (when "扫一扫" is tapped), never at startup.
    val camerax = "1.4.0"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
