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
 */
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

/**
 * Bubble background for a chat role.
 *
 * Tool output gets the darkest surface because it is reference material the user
 * scans past, not a message addressed to them.
 *
 * These four colours are hard-coded rather than taken from the Material colour
 * scheme on purpose. The chat surface is a fixed dark canvas in both light and dark
 * app themes — a transcript is easier to read when its background does not change
 * with the system setting, and the role tints only have to be distinguishable
 * *from each other*, not to harmonise with the rest of the app. Expressing that as
 * theme tokens would mean inventing four scheme slots that no other screen uses.
 */
fun roleColor(role: ChatItem.Role): Color = when (role) {
    // Blue-ish for the person: the one voice that is "from outside the machine".
    ChatItem.Role.User -> Color(0xFF2A3550)
    // Slate for the assistant: readable, deliberately not competing with the accent
    // colour used for "running" indicators elsewhere.
    ChatItem.Role.Assistant -> Color(0xFF17233A)
    // Darkest for tool output: present and scrollable, but visually recessed.
    ChatItem.Role.Tool -> Color(0xFF1A1F2B)
    // System notices (errors, cancellations) sit between tool and assistant.
    ChatItem.Role.System -> Color(0xFF20232B)
}
