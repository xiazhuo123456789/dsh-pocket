package dev.dshpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshpocket.core.ChatItem
import dev.dshpocket.core.ChatState
import dev.dshpocket.core.ConnectionState
import dev.dshpocket.core.Phase
import dev.dshpocket.protocol.ApprovalRequest
import dev.dshpocket.protocol.ModelSelection

/**
 * The conversation view.
 *
 * ### The one rule this screen exists to enforce
 *
 * A pressed button is not a stopped task, and an `{accepted:true}` reply is not a
 * completion. [ChatState.StopState] therefore has four values, not two, and this
 * screen renders a distinct banner for each:
 *
 * | state | what it means | what the user sees |
 * |---|---|---|
 * | `None` | nothing in flight | no banner |
 * | `Requested` | cancel accepted, turn still running | "等待电脑确认…" |
 * | `Confirmed` | a `turn/end` arrived after our request | "电脑已确认停止" |
 * | `Unconfirmed` | accepted but no `turn/end` in 15 s | "未被确认，可能仍在运行" |
 *
 * The `Unconfirmed` case is the important one. The honest thing to tell a user
 * whose stop request went unacknowledged is that the task may still be running —
 * not to show a green "stopped" chip and hope. This is the difference between a
 * control app and a toy.
 *
 * ### Auto-scroll
 *
 * Scrolling is keyed on both the item count and the streaming text length, so it
 * follows a long answer as it is produced rather than only jumping when a whole
 * message lands.
 *
 * @param state the single source of truth; this composable holds no task state of
 *   its own beyond the draft text and the confirm-stop dialog.
 * @param connection used for the disconnect banner; a dropped link does not clear
 *   the transcript.
 * @param onApprove `(request, allow)` — the boolean maps to `allowed-once` vs
 *   `rejected`, the only two outcomes the desktop accepts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatState,
    connection: ConnectionState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApprove: (ApprovalRequest, Boolean) -> Unit,
    onDismissError: () -> Unit,
    onSelectModel: (ModelSelection) -> Unit,
    onSelectPermission: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var confirmStop by remember { mutableStateOf(false) }
    // 两个弹层各自一个布尔。刻意不用一个 "哪个弹层打开着" 的枚举：这两个
    // 不可能同时打开（都从同一条 PreferenceBar 触发），枚举只是多一层无意义的编码。
    var pickModel by remember { mutableStateOf(false) }
    var pickPermission by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 跟随末尾，但只在用户本来就在底部时才跟。
    //
    // 两个细节都是修抖动的：
    //
    // 1. **判断用户是否在底部。** 早先无条件滚动，用户想往上翻看前文时每来一个
    //    增量就被拽回底部，屏幕上就是"内容自己在动"。
    // 2. **流式增量用 `scrollToItem`（瞬时）而不是 `animateScrollToItem`。**
    //    模型每秒能吐十几个增量，每个都启动一次动画等于让列表一直在动画中，
    //    看起来就是持续抖动。只有"新增一整条消息"才值得一次动画。
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(state.items.size, state.streamingText.length) {
        val total = state.items.size + if (state.streamingText.isNotEmpty()) 1 else 0
        if (total <= 0 || !nearBottom) return@LaunchedEffect
        if (state.streamingText.isNotEmpty()) {
            listState.scrollToItem(total - 1)
        } else {
            listState.animateScrollToItem(total - 1)
        }
    }

    // A confirmation step, not a direct action: cancelling is irreversible and
    // the user may have tapped the small stop icon by accident.
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("停止当前任务？") },
            text = {
                Text("将向电脑发送取消请求。只有电脑确认后，任务才算真正停止。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    onStop()
                }) { Text("发送停止请求") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("取消") }
            },
        )
    }

    // 两个选择器都是"确认后才发请求"的弹层：选中的那一刻才调用 ViewModel，
    // 关闭则一个字节都不发。之所以不在点开时预先乐观更新，是因为真正的值由
    // 电脑报回来的 projection 决定 —— 先改本地再被回滚会在屏幕上闪一下。
    if (pickModel) {
        ModelPickerDialog(
            catalog = state.modelCatalog,
            current = state.model,
            onPick = { selection ->
                pickModel = false
                onSelectModel(selection)
            },
            onDismiss = { pickModel = false },
        )
    }
    if (pickPermission) {
        PermissionPickerDialog(
            presets = state.permissionPresets,
            current = state.permission,
            onPick = { preset ->
                pickPermission = false
                onSelectPermission(preset)
            },
            onDismiss = { pickPermission = false },
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
                title = {
                    // Title is whatever the desktop last reported via
                    // `session/title`; "会话" is only a placeholder for a session the
                    // desktop has not named yet, never a name we invented.
                    Column {
                        Text(
                            text = state.title ?: "会话",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                },
                // The status chip sits in the app bar so it stays visible while the
                // transcript scrolls — status is the one thing that must not be
                // scrolled away.
                actions = { StatusChip(state.status) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                // Keeps the composer above the IME instead of behind it. Without
                // this the send button is unreachable while typing, which is the
                // single most common complaint about naive chat layouts.
                .imePadding(),
        ) {
            // Connection health first: if the link is down, every other control on
            // this screen is about to fail, so the explanation goes above them.
            ConnectionBanner(connection.phase, connection.baseUrl, connection.error)

            // A dismissable error strip, distinct from the connection banner. This
            // one reports a specific failed action ("发送失败：…") rather than the
            // state of the link, and the user can acknowledge it.
            //
            // 去重：连接类错误只由上面那条横幅负责，这里绝不再重复一遍。
            //
            // 上一版按**字符串全等**去重（`state.error != connection.error`），实测
            // 依然叠出两条同样的红条 —— 因为两处错误是不同时刻、由不同 catch 块写入
            // 的，只要有一边多了个前缀或尾注就不再相等，而去重逻辑对此毫无察觉。
            // 字符串相等本来就是错误的判据：真正要判断的是"这条错误说的是不是同一
            // 件事"，而这件事是**连接**还是**某次动作**。
            //
            // 判据改为来源：连接已处于 Error 时，凡是网关/传输类的错误（code 以
            // `gateway/` 开头，例如 `gateway/mux-failed`、`gateway/http-500`）都归
            // 横幅讲；只有业务性失败（比如"提交决定失败"、会话不存在）才在这里显示。
            val linkDown = connection.phase == Phase.Error
            val chatError = state.error?.takeIf { err ->
                !(linkDown && err.startsWith("gateway/"))
            }
            chatError?.let { err ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = err,
                            // weight(1f) so a long gateway message wraps instead of
                            // pushing the dismiss button off the row.
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = onDismissError) { Text("知道了") }
                    }
                }
            }

            // The stop lifecycle, rendered above the transcript so a "may still be
            // running" warning is not something the user has to scroll to find.
            StopBanner(state.stopState)

            Box(Modifier.weight(1f)) {
                if (state.items.isEmpty() && state.streamingText.isEmpty() && state.loadingHistory) {
                    // The desktop has not answered with a snapshot yet. Showing the
                    // "把任务发给电脑" placeholder here would claim the conversation is
                    // empty when the truth is that we have not been told — and that
                    // misreading is exactly what makes a slow tunnel look like data
                    // loss.
                    LoadingHistory()
                } else if (state.items.isEmpty() && state.streamingText.isEmpty()) {
                    // Empty is only ever "this session has no messages yet". A load
                    // failure reaches the user through the error strip above, so an
                    // empty transcript is never ambiguous with "we could not ask".
                    EmptyState(
                        icon = Icons.Default.AutoAwesome,
                        title = "把任务发给电脑",
                        body = "描述你要 Harness 做的事。推理、文件操作和命令都在电脑上执行。",
                        modifier = Modifier.padding(top = 56.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp,
                        ),
                        // 间距 14dp 而不是 8dp：助手回答现在没有气泡边框，条目之间
                        // 只靠留白区分，间距太小会让两段回答黏成一段。
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // `key = { it.key }` is load-bearing, not decoration: it is
                        // what lets a re-delivered record update its existing bubble
                        // instead of appending a duplicate, and it preserves scroll
                        // position when older items are re-baselined by a snapshot.
                        items(state.items, key = { it.key }) { item ->
                            Bubble(item)
                        }
                        if (state.streamingText.isNotEmpty()) {
                            // The in-progress bubble is synthesised here rather than
                            // stored in `state.items`, so each delta does not rewrite
                            // the whole list. Its key is a fixed sentinel because
                            // exactly one stream can be in flight per session.
                            item(key = "__streaming__") {
                                Bubble(
                                    ChatItem(
                                        key = "__streaming__",
                                        role = ChatItem.Role.Assistant,
                                        text = state.streamingText,
                                        streaming = true,
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // The approval card sits between transcript and composer: it is the
            // thing blocking the turn, so it must be visible without hunting, and
            // it must not be inside the scrolling area where it could scroll away
            // while the desktop is waiting.
            state.approval?.let { approval ->
                ApprovalCard(
                    approval = approval,
                    onAllow = { onApprove(approval, true) },
                    onReject = { onApprove(approval, false) },
                )
            }

            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                // "Busy" disables *sending*, not the whole screen: `Running` means a
                // turn is in flight. Stop remains available — it is handled
                // separately through `stopState` so the user can interrupt.
                busy = state.status == ChatState.Status.Running ||
                    state.status == ChatState.Status.Queued,
                stopState = state.stopState,
                model = state.model,
                permission = state.permission,
                changingPreference = state.changingPreference,
                // 链路断了就别让用户点开一个必定失败的弹层；已经取到的目录也
                // 不再可信（电脑可能已经换了配置），所以两个都给同一个判据。
                preferencesEnabled = connection.phase == Phase.Connected,
                onPickModel = { pickModel = true },
                onPickPermission = { pickPermission = true },
                onSend = {
                    // Trim before the emptiness check so a draft of only spaces
                    // cannot be sent; clear the field only once the text is accepted
                    // for sending, so a refused send does not lose the user's words.
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        onSend(text)
                    }
                },
                // Stop is always confirmed by a dialog first. Cancelling a turn that
                // has already done real work is not undoable, so it must not be a
                // single accidental tap next to the send button.
                onStop = { confirmStop = true },
            )
        }
    }
}

/**
 * Placeholder shown while the desktop's history snapshot is still in flight.
 *
 * Deliberately distinct from [EmptyState]: the shape ("we are waiting") and the
 * copy ("正在读取电脑上的记录") both have to say that the transcript is not empty,
 * it is unknown. Conflating the two is what makes a slow connection read as
 * "my messages are gone".
 */
@Composable
private fun LoadingHistory() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.5.dp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "正在读取电脑上的记录…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Render the stop lifecycle.
 *
 * The four branches are the point of this composable: collapsing them into a
 * boolean would erase the distinction between "we asked" and "the desktop
 * agreed", which is exactly the distinction the whole project is built around.
 * `None` renders nothing so the banner is not permanent furniture.
 */
@Composable
private fun StopBanner(stopState: ChatState.StopState) {
    val (text, color) = when (stopState) {
        ChatState.StopState.None -> return
        ChatState.StopState.Requested -> "已发送停止请求，等待电脑确认…" to PocketColors.warn
        ChatState.StopState.Confirmed -> "电脑已确认停止" to PocketColors.ok
        ChatState.StopState.Unconfirmed -> "停止请求未被确认，任务可能仍在运行" to PocketColors.bad
    }
    Surface(color = color.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

/**
 * 转写里的一条消息。
 *
 * ### 为什么用户句才有气泡，助手回答是纯正文
 *
 * 这是桌面端 DeepSeek Harness 的做法，也是这一轮改版的核心：把**回答**放进一个
 * 灰方块里，等于给每一段正文都加了一圈没有信息量的边框 —— 长回答时整屏都是色块，
 * 密度低、边界多，看起来就像"贴纸没撕干净"（用户原话："回答背景颜色太丑"）。
 *
 * 反过来，只有用户自己说的话才需要气泡：它要跟回答在视觉上分开来源，而且通常很短。
 * 助手回答是这份界面真正的内容，它应该像文档一样铺在背景上，靠字号、行距和段间距
 * 建立层次 —— 这也是为什么 [MarkdownText] 值得认真写。
 *
 * | 角色 | 形态 |
 * |---|---|
 * | User | 右对齐气泡，`primaryContainer` |
 * | Assistant | 全宽纯正文，Markdown 渲染，无底色 |
 * | Tool | 左侧细竖线 + 小号次级文字，读作参考资料 |
 * | System | 居中、小号、更淡 |
 *
 * 出错的消息仍然是气泡（`errorContainer`）：错误需要被看见，需要跟正常回答区分开。
 */
@Composable
private fun Bubble(item: ChatItem) {
    when (item.role) {
        ChatItem.Role.User -> UserBubble(item)
        ChatItem.Role.Assistant -> AssistantBlock(item)
        ChatItem.Role.Tool -> ToolBlock(item)
        ChatItem.Role.System -> SystemLine(item)
    }
}

/** 用户自己说的话：右对齐气泡，宽度上限是屏宽的 85%。 */
@Composable
private fun UserBubble(item: ChatItem) {
    // 错误态优先：发送失败时这条"用户消息"其实是本地生成的失败记录。
    if (item.isError) {
        ErrorBubble(item)
        return
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            // 85% 而不是固定 320dp：小屏上不会占满整行（留出左边界让"谁说的"
            // 一眼可辨），大屏上也不会变成一条横贯全屏的长条。
            modifier = Modifier.fillMaxWidth(0.85f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = item.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * 助手回答：无气泡，全宽，Markdown 渲染。
 *
 * 没有任何背景色是刻意的 —— 回答是这屏的主内容，给它底色只会让文字与屏幕之间
 * 多隔一层，长文时尤其压抑。
 */
@Composable
private fun AssistantBlock(item: ChatItem) {
    if (item.isError) {
        ErrorBubble(item)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        MarkdownText(
            text = item.text,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (item.streaming) {
            Spacer(Modifier.height(6.dp))
            StreamingDots()
        }
    }
}

/**
 * 工具调用：左侧一条竖线 + 小号次级文字。
 *
 * 不用气泡也不用卡片：工具输出是"电脑做了什么"的旁注，读的时候应该能一眼扫过、
 * 需要时才细看。竖线给了它一个左边界，说明它属于上面那次调用，但不会像卡片那样
 * 把版面切碎。
 */
@Composable
private fun ToolBlock(item: ChatItem) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(2.dp)
                .heightIn(min = 20.dp)
                .background(
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(1.dp),
                )
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.toolName ?: "工具",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.text.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 系统提示（比如"任务已在电脑上开始"）：居中、更淡，不抢注意力。 */
@Composable
private fun SystemLine(item: ChatItem) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = item.text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 失败记录：唯一保留气泡的助手侧消息，因为它必须与正常回答明确区分。 */
@Composable
private fun ErrorBubble(item: ChatItem) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Text(
                text = item.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * "正在生成"的提示：三个依次明暗的圆点。
 *
 * 用三个点而不是一个转圈：转圈暗示"在等一个会结束的操作"，而模型输出是**已经
 * 在产出文字**的，点阵更像"还在写"。三个点也足够小，不会像转圈那样在正文下方
 * 压出一块空白。
 */
@Composable
private fun StreamingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 180),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            )
        }
    }
}

/**
 * Inline approval prompt.
 *
 * Shows what the desktop wants to do before it does it, and offers exactly the
 * two outcomes the protocol supports. The affirmative button says **"允许一次"**
 * rather than "允许" on purpose: the desktop has no "always allow" outcome and no
 * stored grant, so a label implying a persistent decision would misrepresent what
 * the button actually does.
 *
 * Reject is rendered as a plain text button and allow as a filled one, so the
 * consequential action is visually distinct without being the accidental default.
 */
@Composable
private fun ApprovalCard(
    approval: ApprovalRequest,
    onAllow: () -> Unit,
    onReject: () -> Unit,
) {
    Surface(
        color = PocketColors.warn.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("需要你的确认", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                text = approval.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onReject) { Text("拒绝") }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = PocketColors.warn,
                ) {
                    TextButton(onClick = onAllow) {
                        Text("允许一次", color = Color(0xFF2A1C00))
                    }
                }
            }
        }
    }
}

/**
 * Message composer plus the send/stop toggle.
 *
 * The same physical slot switches between send and stop because the two actions
 * are mutually exclusive: while a turn is running there is nothing useful to send
 * (a queued prompt would just wait), and while it is idle there is nothing to
 * stop. `enabled = stopState != Requested` prevents a second cancel from being
 * fired while the first is still unacknowledged.
 *
 * `imePadding()` is applied by the parent so the composer rises above the
 * keyboard instead of being covered by it.
 */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    busy: Boolean,
    stopState: ChatState.StopState,
    model: ModelSelection?,
    permission: String?,
    changingPreference: Boolean,
    preferencesEnabled: Boolean,
    onPickModel: () -> Unit,
    onPickPermission: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    // 输入区用 surfaceContainerLow 而不是 surface：它要与正文区拉开一阶，形成
    // "对话在上面、输入在下面"的分层，否则滚动时输入框像是悬在文字上的浮块。
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            // 选择器在输入框**上方**：它是"这句话怎么跑"的前置条件，读起来是
            // 输入区的一部分；放在下方会贴着键盘被顶出可视区。
            PreferenceBar(
                model = model,
                permission = permission,
                changing = changingPreference,
                enabled = preferencesEnabled,
                onPickModel = onPickModel,
                onPickPermission = onPickPermission,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("描述你要 Harness 做的事…") },
                maxLines = 5,
                shape = RoundedCornerShape(22.dp),
                // 无边框 + 填充底，比一圈描边更像"可以写字的地方"，也不会在
                // 正在输入时与聚焦框的蓝线叠成双层。
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            Spacer(Modifier.size(8.dp))
            if (busy) {
                FilledIconButton(
                    onClick = onStop,
                    enabled = stopState != ChatState.StopState.Requested,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "停止")
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
            }
        }
    }
}
