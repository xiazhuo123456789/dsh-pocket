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

private val Ink = Color(0xFF0B1220)
private val InkElevated = Color(0xFF141C2E)
private val Accent = Color(0xFF7C9CFF)
private val AccentSoft = Color(0xFF2A3550)
private val Ok = Color(0xFF4ADE80)
private val Warn = Color(0xFFFBBF24)
private val Bad = Color(0xFFF87171)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF07101F),
    primaryContainer = AccentSoft,
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Ok,
    onSecondary = Color(0xFF06210F),
    background = Ink,
    onBackground = Color(0xFFE8ECF6),
    surface = InkElevated,
    onSurface = Color(0xFFE8ECF6),
    surfaceVariant = Color(0xFF1C2740),
    onSurfaceVariant = Color(0xFFA9B4CC),
    error = Bad,
    onError = Color(0xFF2A0808),
    outline = Color(0xFF3A4761),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color.White,
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF10141C),
    surface = Color.White,
    onSurface = Color(0xFF10141C),
    surfaceVariant = Color(0xFFEDF0F7),
    onSurfaceVariant = Color(0xFF4A5468),
    error = Color(0xFFB3261E),
    outline = Color(0xFFC3CAD9),
)

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

/** Shared semantic colors for status chips. */
object PocketColors {
    val ok = Ok
    val warn = Warn
    val bad = Bad
    val accent = Accent
    val muted = Color(0xFF8A96AE)
}
