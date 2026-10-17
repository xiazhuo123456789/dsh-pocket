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
 * The session list: the first screen after pairing.
 *
 * A "session" is a Harness conversation living on the desktop. This screen only
 * mirrors what `session/list` reports; it never invents an entry, never reorders
 * by anything except the Host's `updatedAt`, and never marks a session as
 * running on its own. The `running` flag is the desktop's own answer.
 *
 * Why the FAB is "new session" rather than "new chat": creating a session is a
 * real Host-side operation (`session/create`) that allocates a working directory
 * and an agent preset. Keeping the two concepts separate stops the UI from
 * implying that a local draft exists before the desktop agreed to one.
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
                title = { Text("会话") },
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
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建会话") },
            )
        },
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
 * Layout note: the status dot sits outside the text column so the title can wrap
 * to two lines without pushing the dot out of alignment.
 */
@Composable
private fun SessionRow(session: SessionSummary, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
            Spacer(Modifier.size(12.dp))
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
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(formatTime(session.updatedAt))
                        if (session.running) append(" · 运行中")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
