package dev.dshpocket.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshpocket.core.ConnectionState
import dev.dshpocket.core.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表：配对后的第一个界面 — 工作区。
 *
 * 「会话」是存在于电脑端 Harness 里的对话，每一个都带自己的工作目录和 agent 预设。
 * 本屏幕只镜像 `session/list` 的返回，不自创条目、不自行排序（只按 Host 的
 * `updatedAt`）、不自行标 running。`running` 标志是桌面端自己的回答。
 *
 * FAB 是「新建文件」而非「新聊天」：创建会话是真实的 Host 端操作
 * （`session/create`），会分配工作目录和 agent preset。文案用「新建文件」是为了
 * 与桌面端「文件」概念对齐——每个会话就是一个文件/文档，有自己的上下文和历史。
 *
 * ### 视觉对齐桌面端
 *
 * - 顶栏使用品牌蓝渐变背景，与桌面端标题栏质感一致
 * - 会话卡片用圆角 + 微底色 + 边框，形成分层表面
 * - 运行中圆点用品牌蓝，停顿态用 muted，不混用 Material primary
 * - 空态用图标 + 文字，而非纯文字
 *
 * @param sessions   Host-reported summaries, newest first as the Host ordered them.
 * @param loading    True while a `session/list` round-trip is in flight.
 * @param connection Drives [ConnectionBanner]; a stale list stays visible behind
 *                   an error banner rather than being cleared.
 * @param onOpen     Called with a session id; the caller follows that session.
 * @param onNew      Called when the user asks for a fresh session.
 * @param onRefresh  Manual re-list.
 * @param onSettings Opens the settings route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    sessions: List<SessionSummary>,
    loading: Boolean,
    connection: ConnectionState,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "DSH Pocket",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Text(
                            "工作区 · ${sessions.size} 个会话",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // Swap the refresh affordance for a spinner instead of
                    // disabling it: a greyed-out icon reads as "broken", while
                    // a spinner reads as "working".
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建文件") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Rendered above the list, not instead of it: see `connection` above.
            ConnectionBanner(connection.phase, connection.baseUrl, connection.error)

            if (sessions.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.AutoAwesome,
                    title = "工作区还没有文件",
                    body = "点右下角「新建文件」，把第一个任务发给电脑上的 Harness。",
                    modifier = Modifier.padding(top = 72.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // `key` is the session id, not the index: when the Host
                    // reorders the list after a turn finishes, Compose moves the
                    // existing rows instead of rebuilding every one of them.
                    items(sessions, key = { it.id }) { s ->
                        SessionCard(s, onClick = { onOpen(s.id) })
                    }
                }
            }
        }
    }
}

/**
 * One tappable session card.
 *
 * 用 surface variant 做底色、14dp 圆角 + 1px 边框，让每一行读起来像桌面端
 * 工作区里的一张会话卡片。运行圆点在文字列外，保证标题换行时圆点不被挤偏。
 * 左侧加一条 3dp 状态条：运行中=品牌蓝，空闲=muted，让状态在缩略列表里
 * 也能一眼看到。
 */
@Composable
private fun SessionCard(session: SessionSummary, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // 边框用 outlineVariant，比 outline 浅一阶，卡片不会看起来像被框住
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            Modifier.padding(start = 0.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧状态条：3dp 宽，和卡片等高（通过 fillMaxHeight 太激进，
            // 改用固定高度 36dp 居中，视觉上像桌面端的 accent bar）
            Box(
                Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .background(
                        if (session.running) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.title?.takeIf { it.isNotBlank() } ?: "未命名",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTime(session.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (session.running) {
                        Box(
                            Modifier
                                .padding(horizontal = 6.dp)
                                .size(3.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        Text(
                            text = "运行中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact timestamp for a list row.
 *
 * Today's entries show only the clock time; older ones gain the date. The cutoff
 * is a rolling 24 hours rather than a calendar-day boundary, because "yesterday
 * at 23:50" is more confusing than "10-16 23:50" when read at 00:10.
 */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return ""
    val now = System.currentTimeMillis()
    val fmt = if (now - ms < 86_400_000L) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(ms))
}
