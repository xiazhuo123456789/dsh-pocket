package dev.dshpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshpocket.core.ChatState

/** Small pill showing the live task status, driven only by Host signals. */
@Composable
fun StatusChip(status: ChatState.Status, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        ChatState.Status.Idle -> "空闲" to PocketColors.muted
        ChatState.Status.Queued -> "排队中" to PocketColors.warn
        ChatState.Status.Running -> "运行中" to PocketColors.accent
        ChatState.Status.AwaitingApproval -> "等待审批" to PocketColors.warn
        ChatState.Status.Done -> "已完成" to PocketColors.ok
        ChatState.Status.Failed -> "失败" to PocketColors.bad
        ChatState.Status.Cancelled -> "已取消" to PocketColors.muted
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.16f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

/** One-line connection banner; always explains the failure instead of hiding it. */
@Composable
fun ConnectionBanner(
    phase: dev.dshpocket.core.Phase,
    baseUrl: String?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    if (phase == dev.dshpocket.core.Phase.Connected && error == null) return
    val (text, color) = when (phase) {
        dev.dshpocket.core.Phase.Connected -> (baseUrl ?: "已连接") to PocketColors.ok
        dev.dshpocket.core.Phase.Connecting -> "正在连接…" to PocketColors.warn
        dev.dshpocket.core.Phase.Error -> (error ?: "连接失败") to PocketColors.bad
        dev.dshpocket.core.Phase.Disconnected -> "未连接" to PocketColors.muted
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Foreground color for a bubble role. */
fun roleColor(role: dev.dshpocket.core.ChatItem.Role): Color = when (role) {
    dev.dshpocket.core.ChatItem.Role.User -> Color(0xFF2A3550)
    dev.dshpocket.core.ChatItem.Role.Assistant -> Color(0xFF17233A)
    dev.dshpocket.core.ChatItem.Role.Tool -> Color(0xFF1A1F2B)
    dev.dshpocket.core.ChatItem.Role.System -> Color(0xFF20232B)
}
