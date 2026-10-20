package app.dsandroid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dsandroid.data.Appearance
import androidx.compose.ui.graphics.Color

/**
 * Official-app colours (docs/reference/SCREEN_SPEC.md), shared by the account screens and the API-key client.
 * The accent is #3D63F5 rather than the official #426EFE so white text on accent buttons keeps 4.5:1 contrast; the dark
 * palette is an interpretation until the reference capture measures the official one (ContrastTest checks its pairs).
 */
val AccentBlue = Color(0xFF3D63F5)

/**
 * Every Material slot is set: dialogs, sheets, menus, switches and snackbars read the surface-container, secondary and
 * inverse colours, whose Material defaults are lavender. Dialogs are white in light mode and dark grey in dark mode, as the
 * official app draws them; no tonal tint is mixed in.
 */
internal val LightChatColors = lightColorScheme(
    primary = AccentBlue, onPrimary = Color.White, primaryContainer = Color(0xFFE8EEFF), onPrimaryContainer = Color(0xFF3154C7),
    secondary = AccentBlue, onSecondary = Color.White, secondaryContainer = Color(0xFFE8EEFF), onSecondaryContainer = Color(0xFF3154C7),
    tertiary = AccentBlue, onTertiary = Color.White, tertiaryContainer = Color(0xFFE8EEFF), onTertiaryContainer = Color(0xFF3154C7),
    background = Color.White, onBackground = Color(0xFF1A1A1A), surface = Color.White, onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF5F6F8), onSurfaceVariant = Color(0xFF6E7178), surfaceTint = Color.White,
    outline = Color(0xFF8E9198), outlineVariant = Color(0xFFE9EAED),
    inverseSurface = Color(0xFF2B2C30), inverseOnSurface = Color(0xFFF2F2F4), inversePrimary = Color(0xFF8FA8FF),
    surfaceBright = Color.White, surfaceDim = Color(0xFFE9EAED), surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFF0F1F3),
)

internal val DarkChatColors = darkColorScheme(
    primary = Color(0xFF8FA8FF), onPrimary = Color(0xFF0E1F57), primaryContainer = Color(0xFF26304A), onPrimaryContainer = Color(0xFFD6E0FF),
    secondary = Color(0xFF8FA8FF), onSecondary = Color(0xFF0E1F57), secondaryContainer = Color(0xFF26304A), onSecondaryContainer = Color(0xFFD6E0FF),
    tertiary = Color(0xFF8FA8FF), onTertiary = Color(0xFF0E1F57), tertiaryContainer = Color(0xFF26304A), onTertiaryContainer = Color(0xFFD6E0FF),
    background = Color(0xFF161618), onBackground = Color(0xFFECECEF), surface = Color(0xFF161618), onSurface = Color(0xFFECECEF),
    surfaceVariant = Color(0xFF232428), onSurfaceVariant = Color(0xFFA1A3AA), surfaceTint = Color(0xFF161618),
    outline = Color(0xFF7C7F87), outlineVariant = Color(0xFF34363C),
    inverseSurface = Color(0xFFE6E6E9), inverseOnSurface = Color(0xFF1A1A1C), inversePrimary = AccentBlue,
    surfaceBright = Color(0xFF2E3034), surfaceDim = Color(0xFF161618), surfaceContainerLowest = Color(0xFF111113),
    surfaceContainerLow = Color(0xFF1E1F23), surfaceContainer = Color(0xFF232428), surfaceContainerHigh = Color(0xFF26272B),
    surfaceContainerHighest = Color(0xFF2E3034),
)

/** Dialogs and sheets use the large shape: 20 dp corners, as the official app's. */
internal val OfficialShapes = Shapes(extraLarge = RoundedCornerShape(20.dp))

/** Tokens the official layout uses that Material's scheme has no slot for. */
@Immutable
data class OfficialColors(
    val userBubble: Color, val composerSurface: Color, val composerBorder: Color, val placeholder: Color,
    val groupedBackground: Color, val groupSurface: Color, val divider: Color, val destructive: Color, val drawerSurface: Color,
)

val LightOfficialColors = OfficialColors(
    userBubble = Color(0xFFF3F7FF), composerSurface = Color.White, composerBorder = Color(0xFFE9EAED), placeholder = Color(0xFF8E9198),
    groupedBackground = Color(0xFFF5F6F8), groupSurface = Color.White, divider = Color(0xFFEDEEF0), destructive = Color(0xFFD93A33),
    drawerSurface = Color(0xFFF9FAFB),
)

val DarkOfficialColors = OfficialColors(
    userBubble = Color(0xFF26304A), composerSurface = Color(0xFF222327), composerBorder = Color(0xFF34363C), placeholder = Color(0xFF7C7F87),
    groupedBackground = Color(0xFF0F0F11), groupSurface = Color(0xFF1E1F23), divider = Color(0xFF2C2E33), destructive = Color(0xFFFF6B63),
    drawerSurface = Color(0xFF1B1C1F),
)

val LocalOfficialColors = staticCompositionLocalOf { LightOfficialColors }

private val OfficialBody = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 24.sp)

/** The official type scale (system sans-serif; the official app bundles no font). */
internal val OfficialTypography = Typography(
    bodyLarge = OfficialBody, bodyMedium = OfficialBody.copy(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = OfficialBody.copy(fontSize = 13.sp, lineHeight = 19.sp),
    labelSmall = OfficialBody.copy(fontSize = 11.sp, lineHeight = 16.sp), labelMedium = OfficialBody.copy(fontSize = 12.sp, lineHeight = 16.sp),
    titleMedium = OfficialBody.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleLarge = OfficialBody.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    headlineSmall = OfficialBody.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
)

/** The type scale at the user's text size (Settings > Font size); only the styles set above, which all have sizes. */
internal fun Typography.scaled(factor: Float): Typography {
    if (factor == 1f) return this
    fun TextStyle.by() = copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)
    return copy(bodyLarge = bodyLarge.by(), bodyMedium = bodyMedium.by(), bodySmall = bodySmall.by(), labelSmall = labelSmall.by(),
        labelMedium = labelMedium.by(), titleMedium = titleMedium.by(), titleLarge = titleLarge.by(), headlineSmall = headlineSmall.by())
}

@Composable
fun ChatTheme(appearance: Appearance = Appearance.SYSTEM, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val dark = when (appearance) { Appearance.SYSTEM -> isSystemInDarkTheme(); Appearance.LIGHT -> false; Appearance.DARK -> true }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale.coerceIn(0.85f, 1.3f)),
        LocalOfficialColors provides if (dark) DarkOfficialColors else LightOfficialColors) {
        MaterialTheme(colorScheme = if (dark) DarkChatColors else LightChatColors, typography = OfficialTypography, shapes = OfficialShapes, content = content)
    }
}
