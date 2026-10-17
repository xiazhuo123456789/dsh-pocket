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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshpocket.core.ChatItem
import dev.dshpocket.core.ChatState
import dev.dshpocket.core.ConnectionState
import dev.dshpocket.protocol.ApprovalRequest

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
) {
    var draft by remember { mutableStateOf("") }
    var confirmStop by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Follow the tail as content arrives. `+1` accounts for the streaming bubble,
    // which is not yet an item in `state.items`.
    LaunchedEffect(state.items.size, state.streamingText.length) {
        val total = state.items.size + if (state.streamingText.isNotEmpty()) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                // The status chip sits in the app bar so it stays visible while the
                // transcript scrolls — status is the one thing that must not be
                // scrolled away.
                actions = { StatusChip(state.status) },
            )
        },
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
            state.error?.let { err ->
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
                if (state.items.isEmpty() && state.streamingText.isEmpty()) {
                    // Empty is only ever "this session has no messages yet". A load
                    // failure reaches the user through the error strip above, so an
                    // empty transcript is never ambiguous with "we could not ask".
                    EmptyState(
                        title = "把任务发给电脑",
                        body = "描述你要 Harness 做的事。推理、文件操作和命令都在电脑上执行。",
                        modifier = Modifier.padding(top = 56.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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
 * One chat bubble.
 *
 * Alignment carries meaning: user messages sit right, everything the desktop
 * produced sits left. Tool output uses a dimmer surface so it reads as reference
 * material rather than as something addressed to the user.
 *
 * `widthIn(max = 320.dp)` keeps a long line from spanning the full width on a
 * tablet, which would make the transcript hard to follow.
 */
@Composable
private fun Bubble(item: ChatItem) {
    val isUser = item.role == ChatItem.Role.User
    val isTool = item.role == ChatItem.Role.Tool
    val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bg = when {
        item.isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isTool -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val fg = when {
        item.isError -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 14.dp,
            ),
            color = bg,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (isTool) {
                    Text(
                        text = "工具 · ${item.toolName ?: "调用"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                }
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = fg,
                )
                if (item.streaming) {
                    Spacer(Modifier.height(4.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                    )
                }
            }
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
                        Text("允许一次", color = androidx.compose.ui.graphics.Color(0xFF2A1C00))
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
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("描述你要 Harness 做的事…") },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            Spacer(Modifier.size(8.dp))
            if (busy) {
                FilledIconButton(
                    onClick = onStop,
                    enabled = stopState != ChatState.StopState.Requested,
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "停止")
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}
