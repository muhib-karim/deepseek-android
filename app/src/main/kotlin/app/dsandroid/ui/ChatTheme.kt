package app.dsandroid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.dsandroid.data.Appearance
import androidx.compose.ui.graphics.Color

val AccentBlue = Color(0xFF3D63F5)

@Composable
fun ChatTheme(appearance: Appearance = Appearance.SYSTEM, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val dark = when (appearance) { Appearance.SYSTEM -> isSystemInDarkTheme(); Appearance.LIGHT -> false; Appearance.DARK -> true }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFA9BAFF), onPrimary = Color(0xFF102261), primaryContainer = Color(0xFF24304F), onPrimaryContainer = Color(0xFFDCE4FF),
        background = Color(0xFF121212), onBackground = Color(0xFFF1F1F1), surface = Color(0xFF121212), onSurface = Color(0xFFF1F1F1),
        surfaceVariant = Color(0xFF242424), onSurfaceVariant = Color(0xFFB6B6BD), outlineVariant = Color(0xFF383838),
    ) else lightColorScheme(
        primary = AccentBlue, onPrimary = Color.White, primaryContainer = Color(0xFFE8EEFF), onPrimaryContainer = Color(0xFF294BC3),
        background = Color.White, onBackground = Color(0xFF202124), surface = Color.White, onSurface = Color(0xFF202124),
        surfaceVariant = Color(0xFFF5F5F7), onSurfaceVariant = Color(0xFF686B73), outlineVariant = Color(0xFFE8E9ED),
    )
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale.coerceIn(0.85f, 1.3f))) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
