package app.dsandroid.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import app.dsandroid.core.attachments.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

data class Upload(val id: String, val name: String, val kind: AttachmentKind, val file: File, val text: String = "") {
    fun content(vision: Boolean) = AttachmentContent(name, kind, text,
        if (vision && kind == AttachmentKind.IMAGE) AttachmentImporter.imageData(file) else null)
}

class AttachmentImporter(private val context: Context) {
    fun import(uri: Uri): Upload {
        require(uri.scheme == "content") { "Choose a document from the system picker." }
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty().substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}]"), " ").take(160).ifBlank { "attachment" }
        val kind = AttachmentKind.detect(resolver.getType(uri), name)
        require(kind != AttachmentKind.UNKNOWN) { "This file type is not readable. Choose PDF, DOCX, text, or an image." }
        val directory = File(context.filesDir, "uploads").apply { check(isDirectory || mkdirs()) }
        val id = UUID.randomUUID().toString()
        val file = File(directory, id)
        try {
            // Immediate private copy: no persistable URI grant is needed or retained.
            resolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 20 * 1024 * 1024) { "Choose a file smaller than 20 MB." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("The selected document could not be opened.")
            val text = when (kind) {
                AttachmentKind.PDF -> file.inputStream().use(PdfTextExtractor::extract)
                AttachmentKind.DOCX -> file.inputStream().use { DocxTextExtractor.extract(it) }
                AttachmentKind.TEXT -> file.inputStream().use { PlainTextAttachment.read(it) }
                else -> ""
            }
            return Upload(id, name, kind, file, text)
        } catch (error: Exception) { file.delete(); throw error }
        finally {
            if (uri.authority == "${context.packageName}.artifacts" && uri.pathSegments.firstOrNull() == "camera") {
                val cameraName = uri.lastPathSegment.orEmpty()
                if (Regex("[a-fA-F0-9-]+\\.jpg").matches(cameraName)) File(File(context.cacheDir, "camera"), cameraName).delete()
            }
        }
    }

    companion object {
        fun imageData(file: File): String {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image format cannot be decoded." }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3136) sample *= 2
            val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
            try {
                val ratio = minOf(1f, 1568f / maxOf(decoded.width, decoded.height))
                val jpeg = Bitmap.createBitmap((decoded.width * ratio).roundToInt().coerceAtLeast(1),
                    (decoded.height * ratio).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    Canvas(jpeg).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(decoded, null, android.graphics.Rect(0, 0, jpeg.width, jpeg.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    }
                    val output = ByteArrayOutputStream()
                    check(jpeg.compress(Bitmap.CompressFormat.JPEG, 85, output))
                    return "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                } finally { jpeg.recycle() }
            } finally { decoded.recycle() }
        }
    }
}
