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
 * Color tokens.
 *
 * These are deliberately *not* Material's default palette. The app is a control
 * surface for a long-running process, and the important colors are semantic:
 * green means the desktop confirmed something finished, amber means "waiting on
 * a human or on the desktop", red means the desktop reported a failure. Using
 * raw Material roles would let a themed accent color collide with a status
 * meaning, which is exactly the kind of ambiguity this app must avoid.
 *
 * Dark is the intended default (a phone next to a dark desktop), but light is
 * provided so the app does not fight a user who runs the system in light mode.
 */
private val Ink = Color(0xFF0B1220)
private val InkElevated = Color(0xFF141C2E)
private val Accent = Color(0xFF7C9CFF)
private val AccentSoft = Color(0xFF2A3550)
private val Ok = Color(0xFF4ADE80)
private val Warn = Color(0xFFFBBF24)
private val Bad = Color(0xFFF87171)

/**
 * Dark scheme.
 *
 * `onPrimary` is near-black rather than white because [Accent] is a light blue;
 * white text on it would fail contrast.
 */
private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF07101F),
    primaryContainer = AccentSoft,
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Ok,
    onSecondary = Color(0xFF06210F),
    background = Ink,
    onBackground = Color(0xFFE8ECF6),
    // Surfaces are lighter than the background so cards read as "raised"
    // without needing elevation shadows, which are nearly invisible on dark.
    surface = InkElevated,
    onSurface = Color(0xFFE8ECF6),
    surfaceVariant = Color(0xFF1C2740),
    onSurfaceVariant = Color(0xFFA9B4CC),
    error = Bad,
    onError = Color(0xFF2A0808),
    outline = Color(0xFF3A4761),
)

/**
 * Light scheme.
 *
 * The status colors ([PocketColors]) are shared across both schemes; they were
 * picked to stay legible on light and dark surfaces alike.
 */
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
 * Semantic status colors, kept outside [MaterialTheme] on purpose.
 *
 * A status color must mean the same thing regardless of the surrounding theme.
 * If these lived in the color scheme, switching to light mode could change what
 * "confirmed by the desktop" looks like.
 */
object PocketColors {
    /** The desktop confirmed the operation. */
    val ok = Ok

    /** Waiting on a human (approval) or on the desktop (queued, connecting). */
    val warn = Warn

    /** The desktop reported a failure, or a request could not be confirmed. */
    val bad = Bad

    /** Active / in-flight. */
    val accent = Accent

    /** Neutral or unknown. Never used to imply success. */
    val muted = Color(0xFF8A96AE)
}
