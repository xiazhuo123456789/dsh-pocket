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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshpocket.core.ChatItem
import dev.dshpocket.core.ChatState
import dev.dshpocket.core.Phase

/**
 * Small pill showing the live task status.
 *
 * The mapping from [ChatState.Status] to a label is the single place where the
 * app's status vocabulary is defined. Every value here originates from a Host
 * signal (see `PocketViewModel.handleEvent`); none is inferred locally. In
 * particular [ChatState.Status.Done] is only ever set by a `turn/end` event whose
 * `reason.kind` is `completed` or `max-tokens` — both mean "the turn ended normally",
 * one because the model finished and one because it hit the token ceiling.
 */
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
        // 掉线后不再假装知道。见 [ChatState.Status.Unknown] 的 KDoc：显示"未知"
        // 而不是冻结在最后的「运行中」，是因为后者会跟下面的红色断线横幅自相矛盾。
        ChatState.Status.Unknown -> "状态未知" to PocketColors.muted
    }
    // A 16% tint of the semantic color: readable in both themes without the pill
    // competing with the chat content behind it.
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

/**
 * One-line connection banner.
 *
 * Contract: this composable always explains a failure rather than hiding it, and
 * renders nothing at all when the connection is healthy. That asymmetry is
 * intentional — a permanently visible "connected" bar trains people to ignore
 * the bar, which is the opposite of what a remote-control app needs.
 *
 * When [error] is non-null it wins even if [phase] says Connected, because a
 * stale phase plus a fresh error means the error is newer.
 */
@Composable
fun ConnectionBanner(
    phase: Phase,
    baseUrl: String?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    if (phase == Phase.Connected && error == null) return
    val (text, color) = when {
        error != null -> error to PocketColors.bad
        phase == Phase.Connected -> (baseUrl ?: "已连接") to PocketColors.ok
        phase == Phase.Connecting -> "正在连接…" to PocketColors.warn
        else -> "未连接" to PocketColors.muted
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
                // Errors are allowed to be long (they often contain a gateway
                // error code and message) but not to push the screen apart.
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Small dimmed heading used to group rows inside the settings screen.
 *
 * Exists so section headers look the same everywhere instead of each screen
 * inventing its own spacing.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Centered placeholder for a genuinely empty list.
 *
 * Deliberately never used for "we failed to load" — that case must go through
 * [ConnectionBanner] with a real error, so an empty screen is never ambiguous
 * between "nothing here" and "we could not ask".
 *
 * @param icon  Leading icon — uses a muted tint so it does not compete with
 *              the title. Pass [Icons.Default.Inbox] as a neutral default.
 * @param title Big heading.
 * @param body  Secondary explanation.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * 聊天气泡的底色与文字色，跟随当前主题（明/暗）。
 *
 * ### 为什么改成跟随主题（之前的写法是错的）
 *
 * 早先这里返回四个固定的深色（`#232324` 之类）作为气泡底色，理由是"对齐桌面端
 * 的深色转写画布"。这在深色主题下没问题，但在**浅色主题**下每一句话都是一块
 * 近黑色的方块压在白色背景上 —— 视觉上像贴纸没撕干净，而且长回复整屏都是黑的，
 * 读起来累。用户实测反馈"回答背景颜色太丑"就是这一条。
 *
 * 桌面端实际上并没有"固定深色画布"这个设定：它的转写区跟随主题，助手段落用
 * 比背景高一阶的中性面，用户句用品牌蓝容器。所以正确做法是把这四种角色映射到
 * [MaterialTheme] 的分层槽位，由主题决定明暗：
 *
 * | 角色 | 背景 | 文字 |
 * |---|---|---|
 * | User | `primaryContainer` | `onPrimaryContainer` |
 * | Assistant | `surfaceContainerHigh` | `onSurface` |
 * | Tool | `surfaceContainerLow` | `onSurfaceVariant` |
 * | System | `surfaceContainer` | `onSurfaceVariant` |
 *
 * 这样明暗两套主题都自动成立，也不再需要手写 `#E0E0E0` 这种写死的文字色 ——
 * 写死的近白色一旦遇到浅色气泡就会变成白字白底。
 *
 * @return 气泡底色 to 文字色。
 */
@Composable
fun roleColors(role: ChatItem.Role): Pair<Color, Color> = when (role) {
    ChatItem.Role.User ->
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    ChatItem.Role.Assistant ->
        MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
    // 工具调用是参考材料而非对人说的话：用更低一阶的表面 + 次级文字色，
    // 让它在视觉上退到后面，但仍然可读。
    ChatItem.Role.Tool ->
        MaterialTheme.colorScheme.surfaceContainerLow to MaterialTheme.colorScheme.onSurfaceVariant
    ChatItem.Role.System ->
        MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurfaceVariant
}
