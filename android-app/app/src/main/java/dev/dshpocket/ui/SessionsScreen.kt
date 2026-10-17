package dev.dshpocket.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshpocket.core.ConnectionState
import dev.dshpocket.core.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未报告工作目录的会话所用的分组键。
 *
 * 用一个不可能与真实路径碰撞的哨兵，而不是 `null`：折叠状态集合是
 * `Set<String>`，`null` 无法作为元素，而伪造一个像路径的字符串又会与真实目录
 * 同名。双下划线在 Windows 合法但极不可能正好是项目根目录名。
 */
private const val ORPHAN_GROUP_KEY = "__未指定目录__"

/**
 * 一个文件夹分组：桌面端某个工作目录下的全部会话。
 *
 * 与桌面端 DSH 的层级一一对应 —— 工作区（这台电脑）→ 文件夹（`cwd`）→
 * 任务（会话）。手机端不发明新的组织方式，只是把 `session/list` 里本来就有的
 * `cwd` 字段显式呈现出来。
 *
 * @param cwd 完整的目录路径；`null` 表示桌面端没有报告工作目录，这些会话归入
 *   “未指定目录”一组，绝不伪造一个路径。
 * @param sessions 该目录下的会话，保持桌面端给的顺序。
 */
private data class FolderGroup(
    val cwd: String?,
    val sessions: List<SessionSummary>,
)

/**
 * 把会话按工作目录分组。
 *
 * ### 为什么按 `cwd` 分组而不是按时间
 *
 * 用户打开这个 App 是要找回“我在那个项目里做的事”，而项目在磁盘上是目录。
 * 按时间平铺会强迫他在一长串“未命名会话”里靠时间戳猜，而这正是上一版最大的
 * 可用性问题：所有会话看起来一样。
 *
 * ### 顺序
 *
 * 组内按 `updatedAt` 降序；组与组之间按各自最新一条的时间降序，让刚动过的
 * 项目浮到顶部。“未指定目录”始终排在最后，因为它是兜底而不是一个真实项目。
 */
private fun groupByFolder(sessions: List<SessionSummary>): List<FolderGroup> {
    val real = sessions.filter { !it.cwd.isNullOrBlank() }
    val orphans = sessions.filter { it.cwd.isNullOrBlank() }

    val groups = real
        .groupBy { it.cwd }
        .map { (cwd, list) ->
            FolderGroup(
                cwd = cwd,
                sessions = list.sortedByDescending { it.updatedAt },
            )
        }
        .sortedByDescending { g -> g.sessions.maxOfOrNull { it.updatedAt } ?: 0L }

    return if (orphans.isEmpty()) {
        groups
    } else {
        groups + FolderGroup(
            cwd = null,
            sessions = orphans.sortedByDescending { it.updatedAt },
        )
    }
}

/**
 * 会话列表：配对后的第一个界面 —— 工作区。
 *
 * 「会话」是存在于电脑端 Harness 里的对话，每一个都带自己的工作目录和 agent
 * 预设。本屏幕只镜像 `session/list` 的返回，不自创条目、不自行排序（除分组
 * 内部按时间）、不自行标 running。`running` 标志是桌面端自己的回答。
 *
 * ### 层级
 *
 * 屏幕按桌面端的真实组织方式渲染：**工作区 → 文件夹 → 任务**。
 * 每个文件夹就是电脑上的一个工作目录（`cwd`），可点击折叠。这样“这个会话是
 * 哪个项目的”不用点进去才知道。
 *
 * ### FAB 是「新建文件」，并且先问文件夹
 *
 * 创建会话是真实的 Host 端操作（`session/create`，接受可选的 `cwd`），桌面端会
 * 把它落在某个工作目录里。文案用「新建文件」而不是「新建会话」，是为了与桌面端
 * 的文件/文档概念对齐 —— 每个会话就是一个带独立上下文和历史的任务文件。
 *
 * 点 FAB 不会立刻创建，而是先让用户选文件夹：直接创建会把新文件丢进电脑端的
 * 默认目录，用户下次回到列表时找不到它落在哪。可选文件夹来自已有会话的 `cwd`
 * （我们无法浏览电脑磁盘，但能列出已经用过的目录），另加一个「电脑默认目录」
 * 作为兜底。
 *
 * @param sessions   Host-reported summaries, newest first as the Host ordered them.
 * @param loading    True while a `session/list` round-trip is in flight.
 * @param connection Drives [ConnectionBanner]; a stale list stays visible behind
 *                   an error banner rather than being cleared.
 * @param onOpen     Called with a session id; the caller follows that session.
 * @param onNew      Called with the chosen folder (`null` = desktop default) when
 *                   the user confirms a new file.
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
    onNew: (String?) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    val groups = remember(sessions) { groupByFolder(sessions) }
    // Collapsed folders, by cwd. Stored as the *collapsed* set rather than an
    // expanded map so the default (everything visible) needs no initialisation,
    // and so a folder the user has never touched can never be accidentally shut.
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // Which folder the pending "new file" belongs to, or null when the chooser is
    // closed. `rememberSaveable` so a rotation mid-decision does not silently
    // create the file in the default folder instead.
    var choosingFolder by rememberSaveable { mutableStateOf(false) }

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
                            text = if (groups.isEmpty()) {
                                "工作区"
                            } else {
                                "工作区 · ${groups.size} 个文件夹 · ${sessions.size} 个任务"
                            },
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
                onClick = { choosingFolder = true },
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
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    groups.forEach { group ->
                        val groupKey = group.cwd ?: ORPHAN_GROUP_KEY
                        val isCollapsed = groupKey in collapsed

                        item(key = "folder-$groupKey") {
                            FolderHeader(
                                group = group,
                                expanded = !isCollapsed,
                                onToggle = {
                                    collapsed = if (isCollapsed) {
                                        collapsed - groupKey
                                    } else {
                                        collapsed + groupKey
                                    }
                                },
                            )
                        }

                        if (!isCollapsed) {
                            // Each task is its own lazy item, not a Column inside
                            // one item: a folder with forty sessions must not
                            // compose forty cards just because the folder is open.
                            items(group.sessions, key = { it.id }) { s ->
                                SessionCard(s, onClick = { onOpen(s.id) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (choosingFolder) {
        FolderPickerDialog(
            folders = groups.mapNotNull { it.cwd },
            onDismiss = { choosingFolder = false },
            onPick = { cwd ->
                choosingFolder = false
                onNew(cwd)
            },
        )
    }
}

/**
 * 「新文件放在哪个文件夹」的选择框。
 *
 * 只列出已经用过的目录 —— 手机端没有列目录的接口，凭空造一个路径输入框会让
 * 用户写出电脑上并不存在的目录。因此这里给的是「已知的 cwd」加一个「电脑默认
 * 目录」兜底，两者都对应桌面端真实存在的落点。
 *
 * @param folders 去重后的已知工作目录，按 [groupByFolder] 的顺序（最近活跃在前）。
 * @param onPick  用户选定的目录；`null` 表示交给桌面端决定。
 */
@Composable
private fun FolderPickerDialog(
    folders: List<String>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件") },
        text = {
            Column {
                Text(
                    "选择这个文件放在哪个文件夹。电脑上新建的会话会落在这个目录里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // Plain rows rather than a LazyColumn: the list is bounded by the
                // number of distinct directories the user has worked in, which is
                // small, and a dialog has no scroll-to-reach affordance.
                folders.forEach { cwd ->
                    PickerRow(
                        label = folderLabel(cwd),
                        detail = cwd,
                        onClick = { onPick(cwd) },
                    )
                }
                PickerRow(
                    label = "电脑默认目录",
                    detail = "由电脑端的 Harness 决定",
                    onClick = { onPick(null) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** One selectable folder inside [FolderPickerDialog]. */
@Composable
private fun PickerRow(
    label: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一个可折叠的文件夹标题。
 *
 * 标题只显示目录名的最后一段（`dsh-pocket` 而不是完整路径）：窄屏上完整路径
 * 会被截断成一串看不出区别的前缀，而最后一段才是项目名。
 */
@Composable
private fun FolderHeader(
    group: FolderGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        label = "folder-arrow",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = folderLabel(group.cwd),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${group.sessions.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "收起" else "展开",
            modifier = Modifier
                .size(18.dp)
                .rotate(arrowRotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 目录名，用于文件夹标题。
 *
 * 只显示最后一段（`dsh-pocket` 而不是 `E:\dsh Harness手机端\dsh-pocket`）：
 * 完整路径在窄屏上会被截断成看不出区别的一串前缀，而最后一段才是项目名。
 * 完整路径放到副标题里（见 [SessionCard]）。
 */
private fun folderLabel(cwd: String?): String {
    if (cwd.isNullOrBlank()) return "未指定目录"
    val trimmed = cwd.trimEnd('\\', '/')
    val base = trimmed.substringAfterLast('\\').substringAfterLast('/')
    return base.ifBlank { cwd }
}

/**
 * One tappable session card.
 *
 * 用 surface container 做底色、12dp 圆角 + 1px 边框，让每一行读起来像桌面端
 * 工作区里的一张会话卡片。运行圆点在文字列外，保证标题换行时圆点不被挤偏。
 * 左侧加一条 3dp 状态条：运行中=品牌蓝，空闲=muted，让状态在缩略列表里
 * 也能一眼看到。
 *
 * 底色用 [MaterialTheme.colorScheme.surfaceContainerHigh] 而不是 `surface`：
 * 页面背景是 `background`，两者只差一阶时卡片会"陷"进背景里。`surfaceContainer`
 * 阶梯就是为了这种"卡片浮在页面上"的层次而存在的，抬高两阶才看得出是卡片。
 */
@Composable
private fun SessionCard(session: SessionSummary, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // 边框用 outlineVariant，比 outline 浅一阶，卡片不会看起来像被框住
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            Modifier.padding(start = 0.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧状态条：3dp 宽、固定高度居中，视觉上像桌面端的 accent bar
            Box(
                Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .background(
                        if (session.running) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.title?.takeIf { it.isNotBlank() } ?: "未命名任务",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
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
