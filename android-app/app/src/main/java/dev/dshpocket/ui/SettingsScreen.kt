package dev.dshpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Scaffold
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
    onReconnect: () -> Unit,
    onUnpair: () -> Unit,
) {
    // Local dialog visibility only. The confirmation is deliberately not a
    // ViewModel concern: it is a transient UI step, and if the screen is recreated
    // the dialog closing is the safe default (the destructive action must be asked
    // again, never resumed).
    var confirmUnpair by remember { mutableStateOf(false) }

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
            InfoRow(label = "许可证", value = "MIT")
            InfoRow(label = "协议", value = "DeepSeek Harness Remote API Gateway")
            Text(
                "本应用不包含任何 AI 推理能力，也不在手机上执行文件操作或命令。" +
                    "所有任务都在你配对的电脑上由 DeepSeek Harness 执行。",
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
