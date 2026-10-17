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
 * Settings, mirroring the design document's list: relay/base address, live
 * connection status, notification toggles, authorized-device management and
 * version/licence.
 *
 * Revocation is deliberately honest: the phone can only drop *its own*
 * credential. Removing the pairing on the desktop side is the authoritative
 * revoke, so the copy says so instead of implying the phone can revoke itself
 * out of the desktop's device list.
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
    onReconnect: () -> Unit,
    onUnpair: () -> Unit,
) {
    var confirmUnpair by remember { mutableStateOf(false) }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("断开并清除凭据？") },
            text = {
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
            SectionLabel("连接", Modifier.padding(start = 16.dp, top = 12.dp))

            InfoRow(
                label = "服务器地址",
                value = paired?.baseUrl ?: "未配对",
            )
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
            connection.hostHome?.let { InfoRow(label = "电脑主目录", value = it) }
            connection.error?.let { InfoRow(label = "最近错误", value = it, valueColor = PocketColors.bad) }
            InfoRow(label = "本机设备 ID", value = paired?.deviceId ?: "—")

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onReconnect, enabled = paired != null) { Text("重新连接") }
                TextButton(onClick = { confirmUnpair = true }, enabled = paired != null) {
                    Text("断开并清除凭据", color = PocketColors.bad)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
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
            SectionLabel("安全", Modifier.padding(start = 16.dp))
            Text(
                "配对凭据等同于电脑的完整控制权限。它保存在本机的加密存储中，不参与系统备份，" +
                    "也不会写入日志。手机端只能删除本机凭据；真正撤销请到电脑的「远程控制」中删除设备。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("关于", Modifier.padding(start = 16.dp))
            InfoRow(label = "版本", value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow(label = "许可证", value = "MIT")
            InfoRow(label = "协议", value = "DeepSeek Harness typert gateway")
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
