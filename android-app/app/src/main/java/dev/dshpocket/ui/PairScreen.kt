package dev.dshpocket.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dshpocket.core.ConnectionState
import dev.dshpocket.core.Phase
import dev.dshpocket.qr.QrDecoder

/**
 * 首次运行界面：把电脑端配对 QR / 链接变成已保存凭据。
 *
 * 桌面端渲染一个 QR，载荷是 `/pair-accept?pair=<token>` URL。扫描会把整个
 * URL 交给应用，但粘贴永远可用，所以流程不依赖相机或相册图片的清晰度。
 *
 * ### 为什么用系统相册选择器而非实时相机预览
 *
 * 配对每台电脑只做一次。一个相机权限、预览 surface、分析器循环及其生命周期
 * 管理，只为了省一张截图。系统 **photo picker** 在现代 Android 上**零权限**，
 * 且覆盖真实工作流（桌面端把 QR 显示在屏幕上，用户截图或拍照）。
 *
 * ### 为什么粘贴是一等公民而非后备
 *
 * QR 是便利而非协议。在锁定的工手机上、或截图解码器读不出的用户，仍须能配对。
 * 文字栏因此是主控件，扫码是快捷方式——这也是为什么扫码的失败信息明确说
 * 「请改用手动粘贴」。
 *
 * ### 本界面刻意不做的事
 *
 * 不校验链接。token 是否过期、是否畸形、是否属于另一台电脑，由桌面端
 * `pair/accept` 回复决定，ViewModel 已经把回复塑形为 [ConnectionState.error]。
 * 在这里重做检查会造出「这个链接能不能用」的第二个真相源。
 *
 * ### 视觉对齐
 *
 * 整体配色对齐桌面端：深色底 + DeepSeek 品牌蓝主按钮。标题与副标题分层
 * 排版，让首屏读起来像桌面端的引导页而非一个朴素的输入框。
 *
 * @param connection drives [Phase.Connecting] (disables input) and surfaces the
 *   ViewModel's error text; this screen adds only its own scan error to it.
 * @param onPair receives the raw link text; validation happens in the ViewModel so
 *   this screen never has to distinguish "not a link" from "expired token".
 */
@Composable
fun PairScreen(
    connection: ConnectionState,
    onPair: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local, transient UI state only: the typed link and a scan-specific error.
    // Neither belongs in the ViewModel — losing them on rotation is acceptable, and
    // keeping them local means the ViewModel has no idea the picker exists.
    var link by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }

    // `Connecting` is the only busy state that matters here: it is set by the
    // ViewModel the moment a pair attempt starts and cleared on success/failure.
    val busy = connection.phase == Phase.Connecting
    val context = LocalContext.current

    // System photo picker: no permission, no camera, returns a content:// URI that
    // the document provider owns. `rememberLauncherForActivityResult` must be called
    // in composition (not inside an onClick), so it is registered here at the top.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        // A null URI means the user backed out — not an error worth reporting.
        if (uri == null) return@rememberLauncherForActivityResult
        scanError = null
        // Decoding can throw on a corrupt or enormous image; any failure is reported
        // as "could not read a QR", which is the only thing the user can act on.
        val decoded = runCatching { QrDecoder.decode(context, uri) }.getOrNull()
        if (decoded.isNullOrBlank()) {
            scanError = "没能从这张图片里识别出二维码，请改用手动粘贴"
        } else {
            // Fill the field as well as submitting, so the user can see what was
            // read and correct it if the image held the wrong QR.
            link = decoded
            onPair(decoded)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Scrollable because a small phone in landscape plus the keyboard can
            // otherwise clip the primary button.
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(40.dp))

        // ---- 品牌头部 ----
        // 与桌面端视觉系统对齐：标题 + 副标题分层，核心承诺一句话说清。
        Text(
            "DSH Pocket",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            "手机负责控制，电脑负责执行。DeepSeek Harness 的所有推理、文件操作和命令"
                + "都留在你的电脑上；手机只发送任务、显示结果、传递你的决定。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // ---- 连接区 ----
        Text("连接你的电脑", style = MaterialTheme.typography.titleLarge)
        Text(
            "在电脑的 DeepSeek Harness 中打开「远程控制」，用手机扫描二维码，或把配对"
                + "链接粘贴到下面。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = link,
            // Typing invalidates a previous scan error: the user is fixing it.
            onValueChange = { link = it; scanError = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("配对链接") },
            placeholder = { Text("https://…/pair-accept?pair=…") },
            // Two lines because a tunnel URL is long and wraps; showing only the
            // tail would hide the token the user is trying to verify.
            minLines = 2,
            keyboardOptions = KeyboardOptions(
                // URL keyboard avoids autocorrect mangling the token.
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            singleLine = false,
            enabled = !busy,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Button(
            onClick = { onPair(link) },
            modifier = Modifier.fillMaxWidth(),
            // Blank input can never pair. A paste of whitespace is trimmed and
            // rejected by parseInvite, so this only blocks the obviously empty case.
            enabled = !busy && link.isNotBlank(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            if (busy) {
                // Spinner replaces the label rather than sitting beside it: the
                // button means one thing at a time, and the swap animates progress
                // without changing the button's size.
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("连接")
            }
        }

        // Secondary action, visually subordinate: scanning is the shortcut, not the
        // requirement, and the button order mirrors that.
        OutlinedButton(
            onClick = { pickImage.launch(PickVisualMediaRequest()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("从相册选择二维码图片")
        }

        // One error line, two possible sources. A local scan failure is more
        // specific than a connection failure, so it wins while it is set; clearing
        // it on the next edit restores the ViewModel's message.
        val error = scanError ?: connection.error
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(16.dp))
        // The disclosure is placed before the user can succeed, not after, so the
        // consequence of pairing is known while it is still a choice.
        Text(
            "配对凭据等同于电脑的完整控制权限，只保存在这台手机的加密存储里。"
                + "电脑端可随时撤销。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            // Restates the paste path at the bottom, where a user who failed to
            // scan will already be looking for a way out.
            Text(
                "不支持扫码？把电脑上显示的配对链接整段复制过来即可。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
