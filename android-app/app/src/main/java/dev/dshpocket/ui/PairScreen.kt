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
 * First-run screen: turn a desktop pairing QR/link into a stored credential.
 *
 * The desktop renders a QR whose payload is a `/pair-accept?pair=<token>` URL.
 * Scanning hands us the whole URL, but pasting it is always available, so the flow
 * never depends on the camera or on a gallery image being legible.
 *
 * ### Why the photo picker instead of a live camera preview
 *
 * Pairing happens once per computer. A camera permission, a preview surface, an
 * analyser loop and the associated lifecycle handling would all exist to save one
 * screenshot. The system photo picker needs **no permission at all** on modern
 * Android and covers the real workflow (the desktop renders the QR on screen, the
 * user screenshots or photographs it). Dropping `CAMERA` from the manifest is a
 * concrete security and reviewability win — `CAMERA` is a permission that draws
 * attention in every store review and privacy audit.
 *
 * ### Why pasting is first-class, not a fallback
 *
 * The QR is a convenience, not the protocol. A user on a locked-down work phone, or
 * one whose screenshot the decoder cannot read, must still be able to pair. The text
 * field is therefore the primary control and the picker is the shortcut — which is
 * also why the picker's failure message explicitly says "请改用手动粘贴".
 *
 * ### What this screen deliberately does not do
 *
 * It does not validate the link. Whether a token is expired, malformed, or for a
 * different desktop is decided by the desktop's `pair/accept` reply, and the
 * ViewModel already shapes that into [ConnectionState.error]. Re-implementing the
 * check here would create two sources of truth for "is this link usable".
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
        Spacer(Modifier.height(28.dp))
        // First paragraph states the product's core claim before asking for
        // anything: the phone controls, the computer executes. Everything below is
        // read in that frame, which is why the security note later is credible.
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
        )

        Button(
            onClick = { onPair(link) },
            modifier = Modifier.fillMaxWidth(),
            // Blank input can never pair. A paste of whitespace is trimmed and
            // rejected by parseInvite, so this only blocks the obviously empty case.
            enabled = !busy && link.isNotBlank(),
        ) {
            if (busy) {
                // Spinner replaces the label rather than sitting beside it: the
                // button means one thing at a time, and the swap animates progress
                // without changing the button's size.
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
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
        ) { Text("从相册选择二维码图片") }

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
            "配对凭据等同于电脑的完整控制权限，只保存在这台手机的加密存储里。电脑端可随时撤销。",
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
