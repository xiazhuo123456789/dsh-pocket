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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Every entry maps to something the desktop pet can do; nothing here is a
 * placeholder. The animation chips use the same clip ids the state machine uses,
 * so a chip and a real host event run exactly the same frames.
 *
 * @param roam whether the pet wanders around the screen on its own.
 * @param size pet width in dp.
 */
@Composable
fun PetActionMenu(
    roam: Boolean,
    size: Int,
    onReaction: (String) -> Unit,
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
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(
                    "大肥鱼",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "点一下让它做动作，拖着它能换位置",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(PET_QUICK_ACTIONS) { action ->
                        OutlinedButton(onClick = { onReaction(action.clip) }) {
                            Text(action.label)
                        }
                    }
                }

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
                            "待机时自己在屏幕上走动",
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

/**
 * Ordered by how often a person actually pokes a pet: greetings first, work
 * states last (those are what the host drives on its own anyway).
 */
val PET_QUICK_ACTIONS = listOf(
    PetQuickAction("戳一下", "poke"),
    PetQuickAction("摸摸头", "head_pat"),
    PetQuickAction("伸懒腰", "idle_stretch"),
    PetQuickAction("打哈欠", "idle_yawn"),
    PetQuickAction("摇尾巴", "tail"),
    PetQuickAction("吃东西", "eat_token"),
    PetQuickAction("开心", "success"),
    PetQuickAction("出错", "error"),
    PetQuickAction("待机", "idle"),
    PetQuickAction("思考", "thinking"),
    PetQuickAction("干活", "working"),
    PetQuickAction("找东西", "working_search"),
    PetQuickAction("敲命令", "working_command"),
    PetQuickAction("等待", "waiting"),
)
