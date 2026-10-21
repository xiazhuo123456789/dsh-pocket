package dev.dshpocket.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * ### Why the categories are collapsible
 *
 * The bundled pack has every animation from the desktop plugin — a hundred-odd
 * clips in thirteen categories. A flat list of that length is unreadable on a
 * phone, and the desktop plugin's own menu solves it the same way: categories,
 * opened one at a time. The first section starts expanded so the sheet is never
 * just a list of headers.
 *
 * @param roam whether the pet wanders around the screen on its own.
 * @param size pet width in dp.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PetActionMenu(
    assets: PetAssets,
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
    var expanded by remember { mutableStateOf(assets.groups.firstOrNull()?.id) }

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
                    .heightIn(max = 560.dp)
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
                        "点一下让它做动作，拖着它能换位置，甩出去会弹",
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))

                assets.groups.forEach { group ->
                    val open = expanded == group.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = if (open) null else group.id }
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            group.id,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "${group.clips.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (open) "▾" else "▸",
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (open) {
                        FlowRow(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            group.clips.forEach { clip ->
                                OutlinedButton(onClick = { onReaction(clip) }) {
                                    Text(assets.label(clip))
                                }
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
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        FilledTonalButton(onClick = { onSay(PET_QUIPS.random()) }) {
                            Text("碎碎念")
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
                            "待机时自己走动，撞到屏幕边会弹回来",
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
                        valueRange = 96f..320f,
                        steps = 55,
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
                Spacer(Modifier.size(2.dp))
            }
        }
    }
}

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
