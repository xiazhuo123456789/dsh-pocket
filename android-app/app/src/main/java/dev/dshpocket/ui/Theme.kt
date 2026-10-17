package dev.dshpocket.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * DSH Pocket 主题 — 与 DeepSeek Harness 桌面端视觉系统对齐。
 *
 * 色值直接取自桌面端 `app.asar` 里的 CSS 自定义属性
 * (`--dsw-static-neutral-bluish-*` / `--dsw-static-deepseek-*`)，保证手机端和
 * 电脑端在同一台桌上看起来是同一套皮肤，而不是「两个独立应用恰好都用了深色」。
 *
 * ### 为什么不用 Material 默认色板
 *
 * 本应用是长时运行进程的控制面板。语义色（绿=电脑确认完成、琥珀=等待人/电脑、
 * 红=电脑报失败）必须与主题色严格分离，否则主题色一换就会和状态含义冲突。
 * 因此语义色放在 [PocketColors] 独立于 [MaterialTheme]。
 *
 * 深色是预期默认（手机常放于一台暗色桌面旁），但浅色也保留，以免与系统浅色
 * 模式的用户打架。
 *
 * ### 来源标注
 *
 * 色值来源：桌面端 `app.asar` 内 CSS 变量定义，由 REA 工具读取。记录于
 * `docs/sources.md` §"Primary sources: the shipped software"。
 */
// ---- DeepSeek 品牌蓝（--dsw-static-deepseek-*）----
private val DsBlue400 = Color(0xFF7AAAFF)
private val DsBlue450 = Color(0xFF5686FE)
private val DsBlue500 = Color(0xFF4176E6)

// ---- 中性蓝灰（--dsw-static-neutral-bluish-*）----
private val Neutral00 = Color(0xFFFFFFFF)
private val Neutral50 = Color(0xFFF9FAFB)
private val Neutral100 = Color(0xFFEBEEF2)
private val Neutral300 = Color(0xFFCFD3D6)
private val Neutral400 = Color(0xFFADB2B8)
private val Neutral500 = Color(0xFF979DA6)
private val Neutral600 = Color(0xFF81858C)
private val Neutral700 = Color(0xFF61666B)
private val Neutral750 = Color(0xFF43454A)
private val Neutral800 = Color(0xFF353638)
private val Neutral850 = Color(0xFF2C2C2E)
private val Neutral875 = Color(0xFF232324)
private val Neutral900 = Color(0xFF1B1B1C)
private val Neutral950 = Color(0xFF151517)
private val Neutral1000 = Color(0xFF0F1115)

// ---- 状态色（--dsw-static-*-*, 与桌面端 state alias 对齐）----
private val Green400 = Color(0xFF22C55E)
private val Green500 = Color(0xFF10B981)
private val Amber500 = Color(0xFFF59E0B)
private val Red400 = Color(0xFFF87171)
private val Red500 = Color(0xFFEF4444)

/**
 * 深色方案 — 与桌面端默认暗色皮肤一致。
 *
 * 背景用 `--dsw-static-neutral-bluish-1000` (#0F1115)，表面逐层抬高到
 * 875 → 850 → 800，与桌面端 `--dsw-alias-bg-layer-*` 的分层逻辑一致：
 * 层级越高（越靠近用户）表面越亮，不靠阴影也能读出层次。
 */
private val DarkColors = darkColorScheme(
    primary = DsBlue500,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = DsBlue400,
    onPrimaryContainer = Color(0xFF0A0F1A),
    secondary = Green500,
    onSecondary = Color(0xFF04220F),
    secondaryContainer = Neutral800,
    onSecondaryContainer = Neutral50,
    background = Neutral1000,
    onBackground = Neutral50,
    // 表面分层：surface 是第一层抬高的卡片底色
    surface = Neutral950,
    onSurface = Neutral50,
    surfaceVariant = Neutral800,
    onSurfaceVariant = Neutral400,
    // ---- 分层表面 ----
    // 桌面端用 `--dsw-alias-bg-layer-0..3` 拉开层次，而不是靠阴影。Material3 的
    // `surfaceContainer*` 就是对应槽位：数字越大越靠近用户、表面越亮。补全它们
    // 之后，Card / Surface / BottomSheet 会自动获得正确的色阶，不需要每个组件
    // 手写颜色，也不会出现"卡片和背景一个颜色"的塌陷。
    surfaceContainerLowest = Neutral1000,
    surfaceContainerLow = Neutral950,
    surfaceContainer = Neutral900,
    surfaceContainerHigh = Neutral875,
    surfaceContainerHighest = Neutral850,
    surfaceDim = Color(0xFF0B0D10),
    surfaceBright = Neutral850,
    error = Red400,
    onError = Color(0xFF2A0808),
    errorContainer = Color(0xFF4A1D1D),
    onErrorContainer = Color(0xFFFFD9D6),
    outline = Neutral750,
    outlineVariant = Neutral800,
    inverseSurface = Neutral50,
    inverseOnSurface = Neutral900,
    scrim = Color(0xFF000000),
)

/**
 * 浅色方案。
 *
 * 状态色 ([PocketColors]) 在两个方案里共享；它们在浅暗表面上都可读。
 *
 * 浅色同样补全了 `surfaceContainer*` 阶梯 —— 深色主题里层次靠"越来越亮"，
 * 浅色里就必须靠"越来越灰"，否则白卡片叠白背景会糊成一片。
 */
private val LightColors = lightColorScheme(
    primary = DsBlue500,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4EDFD),
    onPrimaryContainer = Color(0xFF283142),
    secondary = Green500,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCF5E8),
    onSecondaryContainer = Color(0xFF04321F),
    background = Neutral50,
    onBackground = Neutral900,
    surface = Neutral00,
    onSurface = Neutral900,
    surfaceVariant = Neutral100,
    onSurfaceVariant = Neutral600,
    surfaceContainerLowest = Neutral00,
    surfaceContainerLow = Neutral50,
    surfaceContainer = Neutral100,
    surfaceContainerHigh = Color(0xFFE7EAEE),
    surfaceContainerHighest = Color(0xFFDDE1E6),
    surfaceDim = Color(0xFFE3E6EA),
    surfaceBright = Neutral00,
    error = Red500,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Neutral300,
    outlineVariant = Neutral100,
    inverseSurface = Neutral900,
    inverseOnSurface = Neutral50,
    scrim = Color(0xFF000000),
)

/**
 * Type scale, trimmed to the four roles this app actually uses.
 *
 * Only the used roles are overridden; everything else falls back to Material's
 * defaults. `bodyLarge` is used for chat bubbles, so its 22sp line height is
 * tuned for multi-line reading rather than for short labels.
 */
private val PocketTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

/**
 * App theme.
 *
 * @param darkTheme defaults to the system setting; callers may pin it for
 *   screenshot tests.
 */
@Composable
fun DshPocketTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PocketTypography,
        content = content,
    )
}

/**
 * 语义状态色，独立于 [MaterialTheme]。
 *
 * 一个状态色在任何主题下都必须含义相同。若放进 colorScheme，切到浅色就可能改变
 * 「电脑确认了」长什么样。色值取自桌面端 `--dsw-alias-state-*`。
 */
object PocketColors {
    /** The desktop confirmed the operation. —dsw-static-green-500 */
    val ok = Green500

    /** Waiting on a human or on the desktop. —dsw-static-amber-500 */
    val warn = Amber500

    /** The desktop reported a failure. —dsw-static-red-400 */
    val bad = Red400

    /** Active / in-flight. —dsw-static-deepseek-400 */
    val accent = DsBlue400

    /** Neutral / unknown. —dsw-static-neutral-bluish-500 */
    val muted = Neutral500
}
