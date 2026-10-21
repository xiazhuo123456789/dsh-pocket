package dev.dshpocket.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
 * 宠物设置的快捷入口——配对页右上角那个齿轮打开的就是它。
 *
 * ### 为什么配对页要有这个
 *
 * 配对之前用户进不了「设置」，于是「显示宠物」这唯一的开关被关在门后：宠物
 * 明明不需要电脑也能跑（素材在 APK 里），用户却必须先连上一台电脑才能打开它。
 * 这不是权限问题，是入口问题。桌宠对不少人来说是「先看它可不可爱，再决定要不要
 * 连电脑」，把开关藏在配对之后正好把顺序颠倒了。
 *
 * 卡片样式和 [PetActionMenu] 保持一致：全屏遮罩 + 底部卡片。遮罩负责「点空白关闭」，
 * 卡片吞掉自己的点击。
 */
@Composable
fun PetQuickSettings(
    enabled: Boolean,
    floating: Boolean,
    roam: Boolean,
    size: Int,
    overlayGranted: Boolean,
    onEnabled: (Boolean) -> Unit,
    onFloating: (Boolean) -> Unit,
    onRoam: (Boolean) -> Unit,
    onSize: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 24.dp)
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(vertical = 14.dp)) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        "桌宠",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "素材就在安装包里，不用连电脑也能玩",
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))

                SettingsToggle(
                    title = "显示宠物",
                    subtitle = "在应用里浮着一只小宠物，断线也还在",
                    checked = enabled,
                    onCheckedChange = onEnabled,
                )

                if (enabled) {
                    SettingsToggle(
                        title = "悬浮在其他应用上",
                        subtitle = if (overlayGranted) {
                            "退出本应用也会显示，可以拖着它到处走"
                        } else {
                            "需要「显示在其他应用上层」权限，打开后会跳到系统设置"
                        },
                        checked = floating,
                        onCheckedChange = onFloating,
                    )
                    SettingsToggle(
                        title = "自动漫步",
                        subtitle = "待机时自己在屏幕上走动，碰到边会弹回来",
                        checked = roam,
                        onCheckedChange = onRoam,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("大小", style = MaterialTheme.typography.bodyLarge)
                        Slider(
                            value = size.toFloat(),
                            onValueChange = { onSize(it.roundToInt()) },
                            valueRange = 96f..200f,
                            steps = 25,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        )
                        Text("${size}dp", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
