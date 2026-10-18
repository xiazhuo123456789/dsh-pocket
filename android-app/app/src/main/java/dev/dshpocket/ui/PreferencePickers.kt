package dev.dshpocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshpocket.protocol.ModelCatalog
import dev.dshpocket.protocol.ModelGroup
import dev.dshpocket.protocol.ModelOption
import dev.dshpocket.protocol.ModelSelection
import dev.dshpocket.protocol.PermissionPreset
import dev.dshpocket.protocol.ReasoningEffort

/**
 * 模型选择器：先选模型，再（可选）选推理档位。
 *
 * ### 为什么是两步而不是一排平铺
 *
 * 桌面端把 (线路, 模型, 推理档) 当成一个三元组，但手机上一次性展示"9 条线路 ×
 * 20 多个模型 × 每个模型 4 个档位"是一屏放不下、也读不懂的。分成两步后第一步
 * 只回答"用哪个模型"，第二步只回答"想多深"，每个屏都只有一个问题。
 *
 * ### 为什么档位是第二步而不是隐藏
 *
 * 推理档会**改变能不能跑通**：给一个不支持 `high` 的线路发 `high`，桌面会直接
 * 拒掉整条 prompt（`UNSUPPORTED_REASONING_EFFORT`），而错误在发送后才出现。所以
 * 档位必须可见可改；但仅在所选模型确实提供档位时才出现第二步。
 *
 * ### 为什么选完模型还要显示"默认"
 *
 * 列表里那一行 `默认` 是电脑自己的默认三元组。用户能分辨"我选的"和"电脑默认
 * 选的"，才不会在换电脑后困惑为什么模型变了。
 *
 * @param catalog 电脑报回来的目录；`null` 表示还没取到（此时显示说明而不是空列表）。
 * @param current 当前生效的三元组，用于打勾；`null` 时没有任何一项打勾。
 * @param onPick 用户最终确定的 (provider, model, effort)。
 * @param onDismiss 关闭且不改变任何东西。
 */
@Composable
fun ModelPickerDialog(
    catalog: ModelCatalog?,
    current: ModelSelection?,
    onPick: (ModelSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    // 第二步的状态：非 null 时界面显示该模型的档位列表。
    // 用 remember 保存所选模型，是因为第二步要能回到第一步改模型。
    var pending by remember { mutableStateOf<ModelOption?>(null) }

    val target = pending
    if (target != null) {
        EffortStep(
            option = target,
            current = current,
            onBack = { pending = null },
            onPick = { effort -> onPick(ModelSelection(target.provider, target.model, effort)) },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            if (catalog == null) {
                // 明确说"还没取到"而不是显示一个空列表：空列表会被读成
                // "这台电脑一个模型都没有"，那是完全不同的事实。
                Text(
                    "还没有从电脑取到模型列表。检查连接后重试。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column {
                    if (catalog.failures.isNotEmpty()) {
                        // 线路枚举失败是电脑侧的配置问题，属于可行动信息。
                        Text(
                            "有 ${catalog.failures.size} 条线路不可用：${catalog.failures.joinToString("、")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.padding(top = 6.dp))
                    }
                    LazyColumn(
                        // 限制高度：模型多的电脑能列出几十项，弹层不能顶满整屏，
                        // 否则"取消"按钮会被挤出可视区。
                        modifier = Modifier.heightIn(max = 420.dp),
                    ) {
                        items(catalog.groups, key = { it.id }) { group ->
                            ModelGroupBlock(
                                group = group,
                                current = current,
                                defaultSelection = catalog.defaultSelection,
                                onSelect = { option -> pending = option },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 一个线路分组：组名 + 组内模型。
 *
 * 组名用 [ModelGroup.name]（电脑给的人类可读名，如「DeepSeek」），不是 id
 * （如 `deepseek-official`）—— id 是 wire 值，拿来显示会把界面变成配置文件。
 */
@Composable
private fun ModelGroupBlock(
    group: ModelGroup,
    current: ModelSelection?,
    defaultSelection: ModelSelection,
    onSelect: (ModelOption) -> Unit,
) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Text(
            text = group.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
        )
        group.models.forEach { option ->
            PickerRow(
                title = option.displayName,
                subtitle = buildString {
                    option.description?.let { append(it) }
                    // 比 provider + model 而不是只比 model：两个线路可能各有一个
                    // 同名模型，只比名字会把"另一条线路的默认"标成这里。
                    if (option.provider == defaultSelection.provider &&
                        option.model == defaultSelection.model
                    ) {
                        if (isNotEmpty()) append(" · ")
                        append("电脑默认")
                    }
                }.ifEmpty { null },
                // 打勾条件包含 provider：两个线路可能各有一个同名模型，
                // 只比 model 会让两条都打勾。
                selected = current != null &&
                    current.provider == option.provider &&
                    current.model == option.model,
                onClick = { onSelect(option) },
            )
        }
    }
}

/**
 * 第二步：为已选模型挑推理档位。
 *
 * 只列出该模型自己声明的档位（`reasoning.efforts`）。**不补默认值、不猜测**：
 * 给一个只支持 `off/low` 的模型补上 `high`，桌面会拒掉整条 prompt。
 *
 * 若模型不提供档位（列表为空），这里说明"该模型不支持调节"并给一个 `null`
 * 的确认入口 —— `null` 在 wire 上是"省略该字段"，与"发 null"不同。
 */
@Composable
private fun EffortStep(
    option: ModelOption,
    current: ModelSelection?,
    onBack: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val alreadySame = current != null &&
        current.provider == option.provider &&
        current.model == option.model
    val currentEffort = if (alreadySame) current?.reasoningEffort else null

    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(option.displayName) },
        text = {
            if (option.efforts.isEmpty()) {
                Text(
                    "这个模型不提供推理档位调节。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column {
                    Text(
                        "推理档位越高，模型想得越久、越适合难任务，也越慢。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(option.efforts, key = { it.id }) { effort ->
                            EffortRow(
                                effort = effort,
                                selected = currentEffort == effort.id,
                                isDefault = option.defaultEffort == effort.id,
                                onClick = { onPick(effort.id) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (option.efforts.isEmpty()) {
                TextButton(onClick = { onPick(null) }) { Text("使用该模型") }
            } else {
                TextButton(onClick = { onPick(option.defaultEffort) }) { Text("用默认档位") }
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text("返回") }
        },
    )
}

/** 一个推理档位行：名称 + 说明 + 打勾。 */
@Composable
private fun EffortRow(
    effort: ReasoningEffort,
    selected: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
) {
    PickerRow(
        title = effort.name,
        subtitle = effort.description ?: if (isDefault) "该模型的默认档位" else null,
        selected = selected,
        onClick = onClick,
        paddingStart = 4.dp,
    )
}

/**
 * 权限档位选择器。
 *
 * ### 为什么把「危险」单独标出来
 *
 * 三个档位里 `danger-full-access` 允许 agent 写工作区之外、并执行不受限命令。
 * 它们看起来都是同一行文字，用户很容易为了"少一次确认"点最右边那个。这里给它
 * 一个警示图标 + 错误色，并附一句说明它到底放开了什么 —— 这是唯一一处我们主动
 * 解释含义的地方，因为后果不可逆。
 *
 * ### 为什么显示的是电脑报回来的当前值
 *
 * 与 [PreferenceBar] 同样的理由：以电脑为准。用户可能刚在桌面端切过。
 *
 * @param presets 电脑报回来的档位目录；空表示还没取到。
 * @param current 电脑当前强制的档位；`null` = 未知。
 * @param onPick 用户选定的档位 value。
 */
@Composable
fun PermissionPickerDialog(
    presets: List<PermissionPreset>,
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限范围") },
        text = {
            if (presets.isEmpty()) {
                Text(
                    "还没有从电脑取到权限档位。检查连接后重试。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(presets, key = { it.value }) { preset ->
                        PickerRow(
                            title = preset.name,
                            subtitle = presetExplanation(preset.value),
                            selected = current == preset.value,
                            dangerous = preset.dangerous,
                            onClick = { onPick(preset.value) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 三个已知档位的中文说明。
 *
 * 只覆盖电脑确实会发的那三个值；未知值返回 `null`，界面就只显示原始档位名 ——
 * 这样上游新增档位时会显示出来而不是消失，同时我们也**不会**为看不懂的档位编
 * 一句可能的解释。那句解释会直接影响用户对安全边界的判断。
 */
private fun presetExplanation(value: String): String? = when (value) {
    "read-only" -> "只能读文件，不能改动任何东西"
    "workspace-write" -> "只能在当前工作目录里写文件，不能对外执行"
    "danger-full-access" -> "可写工作区之外的文件，并执行不受限的命令"
    else -> null
}

/**
 * 选择器里的一行：标题 + 可选副标题 + 打勾。
 *
 * 整行可点，而不是只有文字可点：手机上要点的目标越大越好，而这里每行都是
 * 一个明确的单选，不存在"点行内链接"的歧义。
 */
@Composable
private fun PickerRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    dangerous: Boolean = false,
    paddingStart: androidx.compose.ui.unit.Dp = 4.dp,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
        else androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(start = paddingStart, end = 6.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (dangerous) {
                        Icon(
                            Icons.Default.WarningAmber,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.padding(end = 4.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // 说明可能较长（模型描述），允许两行后截断，避免单行拉出横向溢出。
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "当前选中",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
