package app.dsandroid.core.screen

import kotlin.math.roundToInt

enum class CaptureScope { PROJECTION_WHILE_VISIBLE, OWN_WINDOW }
data class CaptureSize(val width: Int, val height: Int)

object ScreenCapturePolicy {
    // Since API 29, target 29+ projection needs a foreground service. Never bypass that check.
    fun scope(sdk: Int) = if (sdk in 26..28) CaptureScope.PROJECTION_WHILE_VISIBLE else CaptureScope.OWN_WINDOW
    fun size(width: Int, height: Int): CaptureSize {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, 1280.0 / maxOf(width, height))
        return CaptureSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
    }
}
