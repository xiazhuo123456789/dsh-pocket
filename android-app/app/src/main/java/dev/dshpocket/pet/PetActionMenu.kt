package dev.dshpocket.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The action sheet that opens when the floating pet is tapped.
 *
 * ### Why a full-screen scrim and not a popup
 *
 * The pet lives in a `WRAP_CONTENT` overlay window sized to the sprite. A Compose
 * `Popup` (or a `DropdownMenu`) inside that window would be clipped to the pet's
 * own bounds — the menu would appear as a sliver the width of the pet. So the menu
 * is a second full-screen overlay window: dim scrim to catch "tap anywhere to
 * close", card anchored at the bottom. The scrim is what makes dismissal work on
 * *other apps*, where there is no back key and no Activity to receive a touch.
 *
 * ### Why the actions are grouped
 *
 * The desktop pet's right-click menu is a category tree (待机 / 转向 / 拖拽 /
 * 点击回应 / 移动 / 小动作 / 玩耍 / 吃什么 / 时节 / 文字 / 余额 / 碎碎念 /
 * 工作状态). A flat strip of fourteen chips is not that menu; it is a list where
 * every entry has the same weight, so the user has to read all of them. This sheet
 * keeps the categories that the bundled clip set can actually honour: 互动 / 小动作
 * / 干活 / 结果, plus 碎碎念 as a bubble. Categories whose art we do not ship
 * (时节、文字、余额档位) are deliberately absent rather than filled with a clip
 * that does something else.
 *
 * @param roam whether the pet wanders around the screen on its own.
 * @param size pet width in dp.
 */
@Composable
fun PetActionMenu(
    roam: Boolean,
    size: Int,
    onReaction: (String) -> Unit,
    onSay: ((String) -> Unit)? = null,
    onRoamChange: (Boolean) -> Unit,
    onSizeChange: (Int) -> Unit,
    onResetPosition: () -> Unit,
    onOpenApp: () -> Unit,
    onClosePet: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            // The scrim itself is the dismiss target. It sits under the card, so a
            // tap on the card never reaches it.
            .clickable(onClick = onDismiss),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 24.dp)
                // 吞掉落在卡片上的点击：否则它会穿到下面那层"点任意处关闭"的遮罩上，
                // 用户点一下动作按钮，菜单先关了、动作却没播。
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 14.dp),
            ) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        "大肥鱼",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "点一下让它做动作，拖着它能换位置",
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))

                PET_ACTION_GROUPS.forEach { group ->
                    Text(
                        group.title,
                        modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(group.actions) { action ->
                            OutlinedButton(onClick = { onReaction(action.clip) }) {
                                Text(action.label)
                            }
                        }
                    }
                }

                if (onSay != null) {
                    Text(
                        "说话",
                        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilledTonalButton(onClick = { onSay(PET_QUIPS.random()) }) {
                                Text("碎碎念")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动漫步", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "待机时自己在屏幕上走动、碰到边会弹回来",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = roam, onCheckedChange = onRoamChange)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("大小", style = MaterialTheme.typography.bodyLarge)
                    Slider(
                        value = size.toFloat(),
                        onValueChange = { onSizeChange(it.roundToInt()) },
                        valueRange = 96f..200f,
                        steps = 25,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    Text("${size}dp", style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onResetPosition) { Text("回到原位") }
                    TextButton(onClick = onOpenApp) { Text("打开应用") }
                    TextButton(onClick = onClosePet) { Text("关闭悬浮") }
                }
            }
        }
    }
}

/** One menu chip: the clip id plus the word a user would look for. */
data class PetQuickAction(val label: String, val clip: String)

/** One row of the menu: a desktop-menu category that the bundled art can honour. */
data class PetActionGroup(val title: String, val actions: List<PetQuickAction>)

/**
 * Mirrors the desktop pet's category tree, restricted to clips that exist.
 *
 * 互动 = the things a finger does to a pet. 小动作 = the idle micro-actions it
 * plays by itself. 干活 / 结果 = the host-driven states, exposed so the user can
 * preview what each one looks like without having to make the computer work.
 */
val PET_ACTION_GROUPS = listOf(
    PetActionGroup(
        "互动",
        listOf(
            PetQuickAction("戳一下", "poke"),
            PetQuickAction("摸摸头", "head_pat"),
            PetQuickAction("摇尾巴", "tail"),
            PetQuickAction("拽一下", "dragging"),
        ),
    ),
    PetActionGroup(
        "小动作",
        listOf(
            PetQuickAction("伸懒腰", "idle_stretch"),
            PetQuickAction("打哈欠", "idle_yawn"),
            PetQuickAction("发会儿呆", "idle"),
        ),
    ),
    PetActionGroup(
        "干活",
        listOf(
            PetQuickAction("思考", "thinking"),
            PetQuickAction("干活", "working"),
            PetQuickAction("找东西", "working_search"),
            PetQuickAction("敲命令", "working_command"),
            PetQuickAction("等待", "waiting"),
        ),
    ),
    PetActionGroup(
        "结果",
        listOf(
            PetQuickAction("成功", "success"),
            PetQuickAction("出错", "error"),
            PetQuickAction("吃东西", "eat_token"),
        ),
    ),
)

/**
 * Lines for 碎碎念. Deliberately short and mundane: the desktop pet's whisper is
 * small talk, not a notification — anything that sounds like a status report
 * would be mistaken for the host talking.
 */
val PET_QUIPS = listOf(
    "主人今天也在忙呀～",
    "记得喝水，别一直盯着屏幕。",
    "这条命令我看懂了，但是不想说。",
    "摸鱼十分钟，效率翻一倍。",
    "要不要先跑一遍测试？",
    "我在这儿替你看着呢。",
    "尾巴摇累了，歇一会儿。",
    "有 bug 也不慌，先睡一觉。",
    "这个报错我见过，重启试试。",
    "你忙你的，我不吵。",
)
