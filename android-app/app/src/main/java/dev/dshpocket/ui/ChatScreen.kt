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
import dev.dshpocket.protocol.ApprovalRequest

/**
 * The conversation view.
 *
 * The stop control deliberately has three visual states rather than two: a
 * pressed button is not a stopped task. [ChatState.StopState] only becomes
 * `Confirmed` when the Host reports the turn ended.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatState,
    connection: dev.dshpocket.core.ConnectionState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApprove: (ApprovalRequest, Boolean) -> Unit,
    onDismissError: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var confirmStop by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.items.size, state.streamingText.length) {
        val total = state.items.size + if (state.streamingText.isNotEmpty()) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

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
                    Column {
                        Text(
                            text = state.title ?: "会话",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = { StatusChip(state.status) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            ConnectionBanner(connection.phase, connection.baseUrl, connection.error)

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
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = onDismissError) { Text("知道了") }
                    }
                }
            }

            StopBanner(state.stopState)

            Box(Modifier.weight(1f)) {
                if (state.items.isEmpty() && state.streamingText.isEmpty()) {
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
                        items(state.items, key = { it.key }) { item ->
                            Bubble(item)
                        }
                        if (state.streamingText.isNotEmpty()) {
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
                busy = state.status == ChatState.Status.Running ||
                    state.status == ChatState.Status.Queued,
                stopState = state.stopState,
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        onSend(text)
                    }
                },
                onStop = { confirmStop = true },
            )
        }
    }
}

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
