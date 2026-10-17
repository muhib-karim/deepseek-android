package app.dsandroid.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import app.dsandroid.core.tools.*
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

fun interface SvgRasterizer { fun png(svg: String): ByteArray }

/** AndroidSVG has no registered external-resource resolver; input is additionally sanitized. */
object AndroidSvgRasterizer : SvgRasterizer {
    fun bitmap(svg: String, maxSide: Int = 1600): Bitmap {
        require(maxSide in 1..2048)
        val document = SVG.getFromString(SvgSanitizer.sanitize(svg))
        val viewBox = document.documentViewBox
        val rawWidth = document.documentWidth.takeIf { it.isFinite() && it > 0 }
            ?: viewBox?.width()?.takeIf { it.isFinite() && it > 0 } ?: 1200f
        val rawHeight = document.documentHeight.takeIf { it.isFinite() && it > 0 }
            ?: viewBox?.height()?.takeIf { it.isFinite() && it > 0 } ?: 800f
        val ratio = minOf(1f, maxSide.toFloat() / maxOf(rawWidth, rawHeight))
        val width = (rawWidth * ratio).roundToInt().coerceIn(1, maxSide)
        val height = (rawHeight * ratio).roundToInt().coerceIn(1, maxSide)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawPicture(document.renderToPicture(width, height))
            return bitmap
        } catch (error: Exception) { bitmap.recycle(); throw error }
    }
    override fun png(svg: String): ByteArray {
        val bitmap = bitmap(svg, 2048)
        try {
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}

class CreateImageTool(private val rasterizer: SvgRasterizer = AndroidSvgRasterizer) : Tool {
    override val name = "create_image"
    override val description = "Render a static SVG or SVG chart as a new PNG image on this device."
    override val risk = Risk.SAFE
    override val kind = ToolKind.WRITE
    override val schema = objectSchema(mapOf(
        "svg" to stringSchema("Static SVG markup to render"),
        "fileName" to stringSchema("Optional new .png file name"),
    ), required = listOf("svg"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = (args["fileName"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "image-${UUID.randomUUID()}.png"
        val svg = SvgSanitizer.sanitize(args.stringArg("svg"))
        return writeArtifact(ctx, name, ".png", "image/png", rasterizer.png(svg))
    }
}
