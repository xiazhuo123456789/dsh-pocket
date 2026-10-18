package dev.dshpocket.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 打开摄像头，对着电脑屏幕上的二维码扫一下，直接完成配对。
 *
 * ### 为什么现在要有这个
 *
 * 之前只有两条路：粘贴链接、或从相册选一张截图。两者都要求用户先**把二维码弄到
 * 手机上** —— 要么用另一台设备拍下来再传过来，要么在电脑上截图再发给自己。对一个
 * 「手机控制电脑」的 app，第一次连电脑就要绕这么一圈，是明显不合理的。
 * 而电脑本来就把二维码显示在屏幕上，摄像头就对着它。
 *
 * ### 为什么用 CameraX + ML Kit 而不是 ZXing 解实时帧
 *
 * `QrDecoder`（相册那条路）用的就是 ZXing，它对**一张完整的、清晰的位图**解得很准。
 * 但实时预览是另一回事：帧是 YUV、有运动模糊、有屏幕反光，还要在有限的时间里解完
 * 才不卡预览。ML Kit 的 barcode scanner 就是为这个场景做的（受控的、单格式、
 * 已训练的检测器），并且是**内置模型**（`barcode-scanning` 而非
 * `play-services-mlkit-barcode-scanning`）—— 后者的模型要在首次使用时联网下载，
 * 而「正在给电脑配对」这一刻往往正是网络还没配好的时候，下载失败就等于功能不可用。
 *
 * ### 权限
 *
 * `CAMERA` 只在这一刻请求，且只在用户按下「扫一扫」时。拒绝不是死路：这个弹窗会
 * 说明原因并给一个「用相册」的退路，因为相册那条路完全不需要权限。
 *
 * ### 为什么扫到第一张就停
 *
 * 同一个二维码在预览里会被连续识别几十次。用一个 [AtomicBoolean] 闸门保证
 * `onResult` 只兑现一次 —— 否则一次扫码会触发几十次 `pair/accept`，而配对 token
 * 是一次性的，第二次起必然失败，用户会看到一串莫名其妙的错误。
 *
 * @param onResult 扫到的原始文本（通常是 `/pair-accept?pair=…` 链接）。
 * @param onDismiss 用户关闭，或拒绝相机权限后选择退回。
 */
@Composable
fun CameraScannerDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // 权限状态三态：未请求 / 已授予 / 被拒。刻意在 Composition 里持有一个可变的
    // granted 值，而不是直接查 ContextCompat —— 权限回调之后不会触发重组，屏幕
    // 会一直停在「需要权限」的画面上。
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var asked by remember { mutableStateOf(false) }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok; asked = true }

    // 第一次进来自动问一次。`asked` 守卫保证「拒绝后再进来」不会无限追问 ——
    // Android 在用户勾选「不再询问」后本来也会静默拒绝，重复请求只是白弹。
    LaunchedEffect(Unit) {
        if (!granted && !asked) requestPermission.launch(Manifest.permission.CAMERA)
    }

    Dialog(
        onDismissRequest = onDismiss,
        // 全屏深色是刻意的：扫码时屏幕其余部分越暗，取景框里的二维码对比度越高，
        // 相机自动曝光也更容易稳定。
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (granted) {
                LiveScanner(
                    onResult = { value ->
                        onResult(value)
                        onDismiss()
                    },
                    // 相机起不来时退出的出口。走同一个 onDismiss，让配对页回到
                    // 「可以粘贴 / 可以选相册」的状态。
                    onGiveUp = onDismiss,
                )
            } else {
                PermissionGate(
                    denied = asked,
                    onAsk = { requestPermission.launch(Manifest.permission.CAMERA) },
                )
            }

            // 关闭键固定在左上角、始终可点 —— 无论权限走到哪一步，用户都必须能退出。
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * 摄像头预览 + 取景框 + 识别循环。
 *
 * 只有当权限已授予时才进入这棵子树，所以这里可以无条件地绑定 camera provider。
 */
@Composable
private fun LiveScanner(onResult: (String) -> Unit, onGiveUp: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // `rememberUpdatedState` 是必要的：分析器跑在一个长期存活的执行器线程上，
    // 直接捕获 onResult 会捕获到**首帧**的 lambda。虽然本组件的 onResult 实际上
    // 不变，但这属于「一旦变了就是难查的 bug」的类别，保持正确的写法成本为零。
    val currentOnResult by rememberUpdatedState(onResult)

    // 单线程执行器：ML Kit 的识别是 CPU 密集的，多线程并发识别同一路预览只会
    // 抢核并且让帧堆叠。一个线程 + 丢弃积压帧（STRATEGY_KEEP_ONLY_LATEST）是
    // 官方推荐的组合。
    val executor = remember { Executors.newSingleThreadExecutor() }
    val fired = remember { AtomicBoolean(false) }
    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    // 相机不可用时的说明文字。null = 一切正常（或还没试出来）。
    //
    // 为什么需要这个状态：`bindToLifecycle` 会因为没有后置摄像头、被别的应用占用、
    // 或厂商实现异常而抛异常。原来这里是一句光秃秃的 `runCatching {}`，异常被吞掉，
    // 屏幕就只是一片黑 —— 用户合理地以为是卡死了，而实际上什么都不会再发生。
    // manifest 里 `uses-feature camera required="false"` 允许没摄像头的设备安装，
    // 所以这条路径是真实可达的，不是防御性摆设。
    var cameraError by remember { mutableStateOf<String?>(null) }
    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE 而不是 PERFORMANCE：后者用 SurfaceView，在不支持硬件
            // 合成的设备（或某些模拟器）上会显示为黑屏，且无法被 Compose 的
            // 圆角/裁剪正确裁切。
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 释放摄像头比什么都重要：漏掉这一步，退出弹窗后镜头指示灯还亮着，
            // 用户会合理地怀疑这个 app 在偷拍。
            executor.shutdown()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.getInstance(context).get()
        val scanner: BarcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                // 只认 QR：配对载荷永远是一个 URL 的 QR 码，限定格式能显著降低
                // 误识别（一维条码、人脸旁边的文字都可能被多格式模式抓成候选）。
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            // 只保留最新帧。默认策略会排队，扫码时手机稍微一动就积压十几帧，
            // 识别结果会比画面对不上，用户感觉是「扫了半天没反应」。
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // 降到 1280×720：二维码不需要 4K，而每帧都要过一遍检测器。这是
            // 功耗与识别距离之间最划算的一档。
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .build()

        analysis.setAnalyzer(executor) { imageProxy: ImageProxy ->
            processFrame(imageProxy, scanner) { text ->
                // 闸门只放一次。配对 token 是一次性的，触发两次 = 第二次必定
                // 失败，用户会看到一串无意义的错误。
                if (fired.compareAndSet(false, true)) currentOnResult(text)
            }
        }

        runCatching {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }.onFailure { t ->
            // 三类常见原因分开讲，因为用户的下一步动作完全不同：没有摄像头 =
            // 改用粘贴/相册；被占用 = 关掉别的相机应用再试；其它 = 报 bug。
            // 一律说「打开相机失败」会让前两种人去做无用功。
            cameraError = when {
                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ->
                    "这台设备没有摄像头，无法扫码。请改用粘贴配对链接，或从相册选截图。"
                t is IllegalStateException ->
                    "摄像头正被其它应用占用。请关掉相机类应用后重试，" +
                        "或改用粘贴配对链接 / 从相册选截图。"
                else ->
                    "无法启动摄像头（${t.message ?: t.javaClass.simpleName}）。" +
                        "请改用粘贴配对链接，或从相册选截图。"
            }
        }

        awaitCancellation()
    }

    Box(Modifier.fillMaxSize()) {
        // 相机预览本身。AndroidView 只创建一次 PreviewView，重建会导致黑屏闪烁。
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        // 相机起不来时压在预览上的一层说明。放在预览之上而不是替换它，是为了
        // 保留同一棵组合树（避免相机 provider 被反复绑定/解绑）。
        cameraError?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onGiveUp) {
                        Text("用其它方式配对", color = Color.White)
                    }
                }
            }
        }

        // 取景框：只是一个视觉引导，识别并不限于框内。加它是为了让「把码放进框里」
        // 这个动作有明确反馈，扫码成功率比凭空对着屏幕高得多。
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 48.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(
                    width = 2.dp,
                    color = Color.White.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(24.dp),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "把电脑屏幕上的二维码放进框里",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "识别到就会自动连接，不用按任何键",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            // 手电筒对「电脑屏幕反光」和「光线很暗的房间」都有用，成本只有一行。
            TextButton(onClick = {
                val cam = camera ?: return@TextButton
                torchOn = !torchOn
                cam.cameraControl.enableTorch(torchOn)
            }) {
                Icon(
                    imageVector = if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = if (torchOn) "关闭手电筒" else "打开手电筒",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * 把一帧交给 ML Kit，拿到结果后**无论如何**都要关闭 [ImageProxy]。
 *
 * 这是 CameraX 最容易踩的坑：忘记 `close()` 会让缓冲区永不归还，几帧之后预览
 * 直接冻住，而且不报错 —— 表现就是「扫码界面卡住」。
 */
private fun processFrame(
    imageProxy: ImageProxy,
    scanner: BarcodeScanner,
    onText: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            // 一帧里可能识别出多个，取第一个带文本的即可。
            val text = barcodes.firstOrNull { it.rawValue != null }?.rawValue
            if (!text.isNullOrBlank()) onText(text)
        }
        // 失败是常态（模糊帧、半张码），静默等下一帧。把每一帧的错误显示出来
        // 只会告诉用户「我们还在试」，那不是有用的信息。
        .addOnFailureListener { /* ignored on purpose */ }
        .addOnCompleteListener { imageProxy.close() }
}

/**
 * 没拿到相机权限时的说明页。
 *
 * 文案分两种：还没问过（`denied = false`，正在弹系统对话框）和已经被拒。被拒时
 * 不复述系统对话框，而是说清「还能怎么配对」—— 因为相册和粘贴那两条路完全不需要
 * 权限，用户并不是走进死胡同。
 */
@Composable
private fun PermissionGate(denied: Boolean, onAsk: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (denied) "没有相机权限" else "正在请求相机权限",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (denied) {
                "扫码需要相机权限。你也可以关掉这个窗口，改用「从相册选择二维码图片」" +
                    "或直接粘贴配对链接 —— 这两条路都不需要任何权限。"
            } else {
                "系统会弹出一个授权对话框，允许后才能打开摄像头。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
        if (denied) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAsk, shape = RoundedCornerShape(10.dp)) {
                Text("再试一次")
            }
        }
    }
}
