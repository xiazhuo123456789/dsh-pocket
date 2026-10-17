package dev.dshpocket.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dshpocket.core.ConnectionState
import dev.dshpocket.core.Phase
import dev.dshpocket.qr.QrDecoder

/**
 * First-run screen.
 *
 * The desktop shows a QR whose payload is a `/pair-accept?pair=<token>` URL.
 * Scanning hands us the whole thing, but pasting the link is always available so
 * the flow never depends on the camera or on a gallery image being readable.
 */
@Composable
fun PairScreen(
    connection: ConnectionState,
    onPair: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var link by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }
    val busy = connection.phase == Phase.Connecting
    val context = LocalContext.current

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scanError = null
        val decoded = runCatching { QrDecoder.decode(context, uri) }.getOrNull()
        if (decoded.isNullOrBlank()) {
            scanError = "没能从这张图片里识别出二维码，请改用手动粘贴"
        } else {
            link = decoded
            onPair(decoded)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text("DSH Pocket", style = MaterialTheme.typography.titleLarge)
        Text(
            "手机只负责控制，DeepSeek Harness 负责执行。任务、推理和文件操作都留在电脑上。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Text("连接你的电脑", style = MaterialTheme.typography.titleLarge)
        Text(
            "在电脑的 DeepSeek Harness 中打开「远程控制」，用手机扫描二维码，或把配对链接粘贴到下面。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = link,
            onValueChange = { link = it; scanError = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("配对链接") },
            placeholder = { Text("https://…/pair-accept?pair=…") },
            minLines = 2,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            singleLine = false,
            enabled = !busy,
        )

        Button(
            onClick = { onPair(link) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && link.isNotBlank(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("连接")
            }
        }

        OutlinedButton(
            onClick = { pickImage.launch(PickVisualMediaRequest()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        ) { Text("从相册选择二维码图片") }

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
        Text(
            "配对凭据等同于电脑的完整控制权限，只保存在这台手机的加密存储里。电脑端可随时撤销。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                "不支持扫码？把电脑上显示的配对链接整段复制过来即可。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
