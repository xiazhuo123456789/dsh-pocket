package dev.dshpocket.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshpocket.core.ConnectionState
import dev.dshpocket.core.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表：配对后的第一个界面。
 *
 * 「会话」是存在于电脑端 Harness 里的对话。本屏幕只镜像 `session/list` 的返回，
 * 不自创条目、不自行排序（只按 Host 的 `updatedAt`）、不自行标 running。`running`
 * 标志是桌面端自己的回答。
 *
 * FAB 是「新建会话」而非「新聊天」：创建会话是真实的 Host 端操作
 * （`session/create`），会分配工作目录和 agent preset。分开两个概念，防止 UI
 * 暗示本地草稿在桌面同意之前就已存在。
 *
 * ### 视觉对齐
 *
 * 卡片用圆角 + 微底色（`surface` 抬高一阶），与桌面端的会话列表卡片观感一致。
 * 运行中圆点用品牌蓝，停顿态用 muted，不混用 Material primary。
 *
 * @param sessions   Host-reported summaries, newest first as the Host ordered them.
 * @param loading    True while a `session/list` round-trip is in flight; shows a
 *                   spinner in place of the refresh button so the control cannot
 *                   be double-fired.
 * @param connection Drives [ConnectionBanner]; a stale list stays visible behind
 *                   an error banner rather than being cleared, so a transient
 *                   disconnect does not look like "all your sessions are gone".
 * @param onOpen     Called with a session id; the caller follows that session.
 * @param onNew      Called when the user asks for a fresh session.
 * @param onRefresh  Manual re-list. The ViewModel also refreshes after every
 *                   `turn/end`, so this is for the "I changed something on the
 *                   desktop" case.
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
                        Text("DSH Pocket", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "会话工作区",
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
                text = { Text("新建会话") },
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
                    title = "还没有会话",
                    body = "点右下角新建一个，把任务发给电脑上的 Harness。",
                    modifier = Modifier.padding(top = 48.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // The bottom inset leaves room for the FAB so the last row
                    // is never permanently covered by it.
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // `key` is the session id, not the index: when the Host
                    // reorders the list after a turn finishes, Compose moves the
                    // existing rows instead of rebuilding every one of them.
                    items(sessions, key = { it.id }) { s ->
                        SessionRow(s, onClick = { onOpen(s.id) })
                    }
                }
            }
        }
    }
}

/**
 * One tappable session row.
 *
 * 用 surface variant 做底色、12dp 圆角，让每一行读起来像桌面端工作区里的一张
 * 会话卡片，而非默认的 Material 列表项。运行圆点在文字列外，保证标题换行
 * 时圆点不被挤偏。
 */
@Composable
private fun SessionRow(session: SessionSummary, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Accent = the desktop says this session has a turn in flight.
            // Muted = anything else, including "we don't know yet".
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (session.running) PocketColors.accent else PocketColors.muted
                    )
            )
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    // A session can legitimately have no title yet (the desktop
                    // generates one after the first turn), so this is a display
                    // fallback, not a claim that the session is unnamed.
                    text = session.title?.takeIf { it.isNotBlank() } ?: "未命名会话",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
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
                        Box(Modifier.size(3.dp).clip(CircleShape).background(PocketColors.muted))
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "运行中",
                            style = MaterialTheme.typography.labelSmall,
                            color = PocketColors.accent,
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
 *
 * Returns an empty string for a non-positive timestamp, which is what the Host
 * reports for a session that has never been touched.
 */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return ""
    val now = System.currentTimeMillis()
    val fmt = if (now - ms < 86_400_000L) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(ms))
}
