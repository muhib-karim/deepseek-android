package app.dsandroid.deepseek

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * "Create image" on a reply (the official share card, drawn with this app's own mark and name): the question in a bubble,
 * the reply as plain text, and the notice "AI-generated, for reference only". Nothing but the chosen turn is drawn; the
 * PNG goes to the app's cache and leaves the phone only through the share sheet.
 */
object ShareCard {
    private const val WIDTH = 1080
    private const val MARGIN = 64f
    private const val MAX_HEIGHT = 6000
    private val ACCENT = 0xFF3D63F5.toInt()

    /** Markdown reduced to readable text: markers, emphasis and heading signs go; code and lists stay as written. */
    fun plain(markdown: String): String = markdown
        .replace(Regex("\\[citation:(\\d{1,3})]"), "[$1]")
        .replace(Regex("(?m)^#{1,6}\\s+"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1")
        .replace("```", "")
        .trim()

    fun render(context: Context, question: String, answer: String): File {
        val width = WIDTH - 2 * MARGIN.toInt()
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt(); textSize = 46f; typeface = Typeface.DEFAULT_BOLD }
        val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6E7178.toInt(); textSize = 30f }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt(); textSize = 40f }
        val questionLayout = layout(question.trim().ifEmpty { "(files only)" }, bodyPaint, (width * 0.8f).toInt() - 64, 8)
        val answerLayout = layout(plain(answer), bodyPaint, width, 80)
        val notice = layout("AI-generated, for reference only. Shared from DeepSeek Android, an unofficial client.", smallPaint, width, 3)
        val header = 120f
        val bubbleHeight = questionLayout.height + 56f
        val height = (MARGIN + header + 40f + bubbleHeight + 48f + answerLayout.height + 56f + notice.height + MARGIN).toInt().coerceAtMost(MAX_HEIGHT)
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFFFFFFFF.toInt())
        var top = MARGIN
        drawMark(canvas, MARGIN, top, 96f)
        canvas.drawText("DeepSeek Android", MARGIN + 124f, top + 52f, titlePaint)
        canvas.drawText("Unofficial client · not affiliated with DeepSeek", MARGIN + 124f, top + 96f, smallPaint)
        top += header + 40f
        // The question, in a light bubble on the right like the chat.
        val textWidth = (0 until questionLayout.lineCount).maxOfOrNull { questionLayout.getLineWidth(it) } ?: 0f
        val bubbleWidth = textWidth.coerceAtMost(questionLayout.width.toFloat()) + 64f
        val bubbleLeft = WIDTH - MARGIN - bubbleWidth
        canvas.drawRoundRect(RectF(bubbleLeft, top, WIDTH - MARGIN, top + bubbleHeight), 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF3F7FF.toInt() })
        canvas.save(); canvas.translate(bubbleLeft + 32f, top + 28f); questionLayout.draw(canvas); canvas.restore()
        top += bubbleHeight + 48f
        canvas.save(); canvas.translate(MARGIN, top); answerLayout.draw(canvas); canvas.restore()
        top += answerLayout.height + 32f
        canvas.drawLine(MARGIN, top, WIDTH - MARGIN, top, Paint().apply { color = 0xFFEDEEF0.toInt(); strokeWidth = 2f })
        top += 24f
        canvas.save(); canvas.translate(MARGIN, top); notice.draw(canvas); canvas.restore()
        val directory = File(context.cacheDir, "share-cards").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val file = File(directory, "deepseek-reply-${UUID.randomUUID().toString().take(8)}.png")
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        return file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.artifacts", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share image").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1)).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f).setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()

    /** The app's own mark (chat bubble and spark), as AppMark draws it. */
    private fun drawMark(canvas: Canvas, left: Float, top: Float, size: Float) {
        val k = size / 72f
        fun x(v: Float) = left + (v - 18f) * k
        fun y(v: Float) = top + (v - 22f) * k
        val bubble = Path().apply {
            moveTo(x(28f), y(28f)); lineTo(x(80f), y(28f)); quadTo(x(86f), y(28f), x(86f), y(36f))
            lineTo(x(86f), y(68f)); quadTo(x(86f), y(76f), x(78f), y(76f)); lineTo(x(48f), y(76f)); lineTo(x(30f), y(88f))
            lineTo(x(30f), y(76f)); quadTo(x(22f), y(76f), x(22f), y(68f)); lineTo(x(22f), y(36f))
            quadTo(x(22f), y(28f), x(28f), y(28f)); close()
        }
        canvas.drawPath(bubble, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
        val star = Path().apply {
            moveTo(x(57f), y(37f)); lineTo(x(61f), y(48f)); lineTo(x(72f), y(52f)); lineTo(x(61f), y(56f))
            lineTo(x(57f), y(67f)); lineTo(x(53f), y(56f)); lineTo(x(42f), y(52f)); lineTo(x(53f), y(48f)); close()
        }
        canvas.drawPath(star, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
    }
}
