package app.dsandroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class Glyph { MENU, PLUS, SEND, STOP, THINK, SEARCH, CAMERA, PHOTO, DOCUMENT, CLOSE, CHEVRON, TOOL, CHECK, SHIELD, ERROR }

/** Original line icons; no external icon pack or brand artwork. */
@Composable
fun AppIcon(glyph: Glyph, description: String? = null, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Canvas(modifier.size(22.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1, y1), Offset(x2, y2), 1.8f, StrokeCap.Round)
            when (glyph) {
                Glyph.MENU -> { line(4f, 8f, 20f, 8f); line(4f, 16f, 15f, 16f) }
                Glyph.PLUS -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
                Glyph.CLOSE, Glyph.ERROR -> { line(6f, 6f, 18f, 18f); line(6f, 18f, 18f, 6f) }
                Glyph.SEND -> { line(12f, 20f, 12f, 4f); line(5f, 11f, 12f, 4f); line(19f, 11f, 12f, 4f) }
                Glyph.STOP -> drawRoundRect(color, Offset(6f, 6f), Size(12f, 12f), CornerRadius(2f))
                Glyph.CHEVRON -> { line(7f, 9f, 12f, 14f); line(12f, 14f, 17f, 9f) }
                Glyph.CHECK -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 20f, 6f) }
                Glyph.SEARCH -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = stroke)
                    drawOval(color, Offset(8f, 3f), Size(8f, 18f), style = stroke)
                    line(3f, 12f, 21f, 12f)
                }
                Glyph.THINK -> {
                    drawCircle(color, 7f, Offset(12f, 9f), style = stroke)
                    line(9f, 18f, 15f, 18f); line(10f, 21f, 14f, 21f); line(12f, 10f, 12f, 15f)
                }
                Glyph.CAMERA -> {
                    drawRoundRect(color, Offset(3f, 6f), Size(18f, 14f), CornerRadius(3f), style = stroke)
                    drawCircle(color, 4f, Offset(12f, 13f), style = stroke); line(8f, 3f, 16f, 3f)
                }
                Glyph.PHOTO -> {
                    drawRoundRect(color, Offset(3f, 3f), Size(18f, 18f), CornerRadius(3f), style = stroke)
                    drawCircle(color, 2f, Offset(8f, 8f)); line(4f, 19f, 12f, 11f); line(12f, 11f, 20f, 19f)
                }
                Glyph.DOCUMENT -> {
                    drawRoundRect(color, Offset(5f, 2f), Size(14f, 20f), CornerRadius(2f), style = stroke)
                    line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 13f, 16f)
                }
                Glyph.TOOL -> {
                    drawArc(color, 40f, 280f, false, Offset(12f, 2f), Size(10f, 10f), style = stroke)
                    line(14f, 10f, 4f, 20f); line(16f, 12f, 6f, 22f)
                }
                Glyph.SHIELD -> {
                    val path = Path().apply { moveTo(12f, 2f); lineTo(21f, 6f); lineTo(19f, 16f); lineTo(12f, 22f); lineTo(5f, 16f); lineTo(3f, 6f); close() }
                    drawPath(path, color, style = stroke)
                }
            }
        }
    }
}
