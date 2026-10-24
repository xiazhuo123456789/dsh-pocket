package dev.dshpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import dev.dshpocket.BuildConfig
import dev.dshpocket.core.ConnectionState
import dev.dshpocket.core.Phase
import dev.dshpocket.data.AppSettings
import dev.dshpocket.data.PairedDesktop

/**
 * Settings.
 *
 * ### Why the revocation copy is worded so carefully
 *
 * The phone **cannot revoke itself** from the desktop's device list. The
 * pairing-management surface is deliberately unreachable from a
 * paired remote device, so `revoke` only works from the desktop's own loopback
 * origin. What this screen can do is delete the local credential, which stops
 * *this phone* from authenticating — but the desktop still lists the device until
 * someone removes it there.
 *
 * Presenting "unpair" as if it were a full revoke would be a security-relevant
 * lie: a user who thinks they revoked a lost phone would leave a live credential
 * in the desktop's list. The confirmation dialog therefore states both halves
 * explicitly.
 *
 * ### Why the device id is shown
 *
 * It is the identifier the desktop's device list displays, so showing it lets a
 * user match this phone to an entry when they go to revoke it. It is a credential,
 * so it is only ever displayed here, on an explicitly opened settings screen —
 * never logged, never in a notification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    connection: ConnectionState,
    paired: PairedDesktop?,
    settings: AppSettings,
    onBack: () -> Unit,
    onNotifyDone: (Boolean) -> Unit,
    onNotifyApproval: (Boolean) -> Unit,
    onNotifyDisconnect: (Boolean) -> Unit,
    onSimplifiedChinese: (Boolean) -> Unit,
    onPetEnabled: (Boolean) -> Unit,
    onPetSize: (Int) -> Unit,
    onPetFloating: (Boolean) -> Unit,
    overlayGranted: Boolean,
    onPetRoam: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onUnpair: () -> Unit,
    onChangeAddress: (String) -> Unit,
) {
    // Local dialog visibility only. The confirmation is deliberately not a
    // ViewModel concern: it is a transient UI step, and if the screen is recreated
    // the dialog closing is the safe default (the destructive action must be asked
    // again, never resumed).
    var confirmUnpair by remember { mutableStateOf(false) }

    // Editing the address is the recovery path for the most common failure: the
    // desktop publishes a `trycloudflare` quick tunnel whose hostname changes
    // every time the desktop restarts, so the stored URL starts returning "connection
    // refused" even though this phone is still an accepted device. Re-pairing would
    // throw away a perfectly good device credential, so the address is editable
    // on its own.
    var editingAddress by remember { mutableStateOf(false) }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("断开并清除凭据？") },
            text = {
                // Both halves are stated because they are genuinely different
                // operations, and conflating them is the security-relevant lie this
                // screen exists to avoid: clearing locally does NOT revoke the
                // device on the desktop, so a lost phone would keep a live grant.
                Text(
                    "本机保存的配对凭据会被删除，需要重新配对才能继续控制电脑。\n" +
                        "要彻底撤销这台手机，请在电脑的「远程控制」里删除该设备。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    onUnpair()
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text("取消") }
            },
        )
    }

    if (editingAddress) {
        // 预填当前地址：改地址最常见的情形是「旧地址失效了，我要换成新的」，
        // 从空白框开始等于要求用户先回忆旧值。历史地址则是唯一可能替用户
        // 想起「上次能用的是哪个」的东西 —— 域名是电脑端生成的，手机感知不到。
        var draft by remember(paired?.baseUrl) { mutableStateOf(paired?.baseUrl.orEmpty()) }
        var pasted by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editingAddress = false },
            title = { Text("修改电脑地址") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "电脑上的临时隧道地址每次重启都会变，换了之后旧地址就连不上，" +
                            "但这台手机在电脑上的授权还在。换成新地址就不用重新配对。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        label = { Text("电脑地址") },
                        placeholder = { Text("https://…trycloudflare.com") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (settings.recentAddresses.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "用过的地址",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        settings.recentAddresses.forEach { address ->
                            Text(
                                text = address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { draft = address }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "或者粘贴电脑上整段配对链接，只取其中的地址",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { text ->
                            pasted = text
                            // 只提取 `https://host` 这一段：配对链接里还带着一次性令牌，
                            // 把它当地址存下来既连不上，也等于把令牌写进设置。
                            Regex("""https?://[^/\s]+""").find(text)?.let { draft = it.value }
                        },
                        singleLine = true,
                        label = { Text("配对链接") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editingAddress = false
                        onChangeAddress(draft)
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("连接") }
            },
            dismissButton = {
                TextButton(onClick = { editingAddress = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("设置") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ---- Connection ----------------------------------------------------
            // Everything here is read-only observational data. No control lives in
            // this section except the two explicit buttons below it, so a user
            // scrolling past cannot change the connection by accident.
            SectionLabel("连接", Modifier.padding(start = 16.dp, top = 12.dp))

            InfoRow(
                label = "服务器地址",
                value = paired?.baseUrl ?: "未配对",
            )
            // The status row is colour-coded with the same semantic tokens the
            // banner uses, so "green means the desktop said so" holds everywhere.
            InfoRow(
                label = "状态",
                value = when (connection.phase) {
                    Phase.Connected -> "已连接"
                    Phase.Connecting -> "正在连接…"
                    Phase.Error -> "连接失败"
                    Phase.Disconnected -> "未连接"
                },
                valueColor = when (connection.phase) {
                    Phase.Connected -> PocketColors.ok
                    Phase.Error -> PocketColors.bad
                    Phase.Connecting -> PocketColors.warn
                    Phase.Disconnected -> PocketColors.muted
                },
            )
            // The desktop's home directory comes from the `$events` ready frame.
            // It is the cheapest way to answer "am I actually talking to *my*
            // machine, or to some other tunnel endpoint?".
            connection.hostHome?.let { InfoRow(label = "电脑主目录", value = it) }
            connection.error?.let { InfoRow(label = "最近错误", value = it, valueColor = PocketColors.bad) }
            // Shown only on this explicitly opened screen. It is a credential
            // component: never logged, never in a notification.
            InfoRow(label = "本机设备 ID", value = paired?.deviceId ?: "—")

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Retry without re-pairing: the credential is usually still valid
                // and the failure is usually that the desktop was asleep.
                TextButton(onClick = onReconnect, enabled = paired != null) { Text("重新连接") }
                // Kept separate from "重新连接" because they answer different
                // questions: reconnect retries the *same* address, this one changes
                // where the app points while keeping the device credential.
                TextButton(onClick = { editingAddress = true }, enabled = paired != null) {
                    Text("修改地址")
                }
                TextButton(onClick = { confirmUnpair = true }, enabled = paired != null) {
                    Text("断开并清除凭据", color = PocketColors.bad)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---- Notifications -------------------------------------------------
            // Three independent switches rather than one master toggle: the three
            // events have very different urgency, and a user who finds the
            // connection notifications noisy should not have to give up being told
            // when the desktop is blocked on them.
            SectionLabel("通知", Modifier.padding(start = 16.dp))

            SwitchRow(
                title = "任务完成时通知",
                subtitle = "任务成功或失败时提醒",
                checked = settings.notifyOnDone,
                onCheckedChange = onNotifyDone,
            )
            SwitchRow(
                title = "需要审批时通知",
                subtitle = "电脑等待你确认时提醒",
                checked = settings.notifyOnApproval,
                onCheckedChange = onNotifyApproval,
            )
            SwitchRow(
                title = "连接断开时通知",
                subtitle = "与电脑失去连接时提醒",
                checked = settings.notifyOnDisconnect,
                onCheckedChange = onNotifyDisconnect,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---- 桌宠 ---------------------------------------------------------
            // 这里的开关只管**本地渲染**：素材随 APK 一起安装，不向电脑发任何
            // 请求，所以没配对、断网、电脑关机时都能显示。副标题必须说清楚这一点，
            // 否则用户会以为它和「远程控制」一样依赖连接。
            SectionLabel("桌宠", Modifier.padding(start = 16.dp))

            SwitchRow(
                title = "显示桌宠",
                subtitle = "在应用内漂浮一只小宠物；素材已内置，不连接电脑也会待机和玩耍",
                checked = settings.petEnabled,
                onCheckedChange = onPetEnabled,
            )
            if (settings.petEnabled) {
                PetSizeRow(
                    size = settings.petSize,
                    onSizeChange = onPetSize,
                )
                // 悬浮窗是"看得见"的差异点：宠物从应用里走出来，盖在桌面和其他
                // 应用上面。副标题必须点明它要一项系统权限，并且离开本应用后依然
                // 显示——否则用户会以为它和上面那个开关是同一件事。
                SwitchRow(
                    title = "悬浮在其他应用上",
                    subtitle = if (overlayGranted) {
                        "退出本应用、回到桌面也会显示；可以拖动、点一下有动作菜单"
                    } else {
                        "需要「显示在其他应用上层」权限，打开后会跳到系统设置授权"
                    },
                    checked = settings.petFloating,
                    onCheckedChange = onPetFloating,
                )
                // 自己走动只在待机时发生；任务在跑的时候它在做对应动作，不该乱跑。
                SwitchRow(
                    title = "自动漫步",
                    subtitle = "待机时偶尔自己在屏幕上走一小段，停下时做小动作",
                    checked = settings.petRoam,
                    onCheckedChange = onPetRoam,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---- 对话 ---------------------------------------------------------
            // 这一项容易被误认为"界面语言"，其实是**模型输出语言**：本应用的
            // 界面本来就是中文，回复用的是电脑端模型自己的语言偏好。Remote API
            // 没有"设置回复语言"的接口，手机端唯一正当的办法是在每条消息后附上
            // 一句指令，所以副标题必须写清楚这一点 —— 用户有权知道自己发出的
            // 内容里多了一句不是自己打的话。
            SectionLabel("对话", Modifier.padding(start = 16.dp))

            SwitchRow(
                title = "用简体中文回答",
                subtitle = "会给每条消息附加一句「请用简体中文回答」的指令",
                checked = settings.simplifiedChinese,
                onCheckedChange = onSimplifiedChinese,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---- Security ------------------------------------------------------
            // This paragraph is the security disclosure. It is deliberately not
            // collapsed behind a link: "the credential is full control of your
            // computer" is the single most important thing to know about this app.
            SectionLabel("安全", Modifier.padding(start = 16.dp))
            Text(
                "配对凭据等同于电脑的完整控制权限。它保存在本机的加密存储中，不参与系统备份，" +
                    "也不会写入日志。手机端只能删除本机凭据；真正撤销请到电脑的「远程控制」中删除设备。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---- About ---------------------------------------------------------
            // The last paragraph matters for expectation-setting and for legal
            // clarity: this app performs no inference and executes nothing. The
            // licence line is MIT because that is what this repository ships under;
            // the protocol line names the upstream surface, so nobody mistakes this
            // for an official DeepSeek product.
            SectionLabel("关于", Modifier.padding(start = 16.dp))
            InfoRow(label = "版本", value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow(label = "许可证", value = "MIT（代码）")
            InfoRow(label = "桌宠素材", value = "CC BY-NC-SA 4.0 · 不可商用")
            InfoRow(label = "协议", value = "DeepSeek Harness Remote API Gateway")
            Text(
                "本应用不包含任何 AI 推理能力，也不在手机上执行文件操作或命令。" +
                    "所有任务都在你配对的电脑上由 DeepSeek Harness 执行。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Attribution is a licence obligation, not a courtesy: the character is
            // CC BY-NC-SA 4.0, so the two designers and the upstream project have to be
            // named wherever the artwork is distributed — including inside the app.
            Text(
                "桌宠「大肥鱼」为社区二创形象，非 DeepSeek 官方形象：原型「溟月」出自 B 站 " +
                    "@上善无形，DeepSeek 版设计出自 B 站 @ZipZipPipe；动画素材来自 " +
                    "github.com/PC2005-cloud/dsh-pet。该形象以 CC BY-NC-SA 4.0" +
                    "（署名·非商业·相同方式共享）授权，不可用于任何形式的商业化，因此本应用" +
                    "完全免费、不含广告与内购。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One label/value pair.
 *
 * `maxLines = 4` with ellipsis rather than wrapping indefinitely: an error string
 * from the gateway can be long, and letting it grow would push the rest of the
 * screen off the bottom.
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A labelled switch row.
 *
 * The whole row is not clickable on purpose — the switch itself is the target, so
 * scrolling the list cannot flip a setting by accident.
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 桌宠大小滑块。
 *
 * 为什么用连续滑块而不是「小/中/大」三档：宠物的视觉大小直接决定它挡不挡
 * 聊天内容，而每块屏幕的宽度差得远，三档必然有人两头都不满意。范围与
 * `SettingsStore.PET_MIN_SIZE`/`PET_MAX_SIZE` 保持一致，写入端也会再夹一次。
 */
@Composable
private fun PetSizeRow(size: Int, onSizeChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("大小", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                "$size dp",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = size.toFloat(),
            onValueChange = { onSizeChange(it.roundToInt()) },
            valueRange = 96f..320f,
            steps = 55,
        )
    }
}
