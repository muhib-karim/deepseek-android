package app.dsandroid.attachments

import app.dsandroid.core.attachments.ImageTextStatus
import app.dsandroid.core.attachments.PlainTextAttachment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

internal data class ImageTextResult(val text: String, val status: ImageTextStatus)

/** Bundled Latin model: recognition never needs a model download or an outbound request. */
internal object ImageTextRecognizer {
    private const val TIMEOUT_MS = 15_000L
    // Native ML Kit Tasks cannot be cancelled. Keep their resources until completion and
    // allow only one task in flight, including after a caller cancels or times out.
    private val slot = Semaphore(1)
    private val directExecutor = Executor { it.run() }

    suspend fun extract(file: File): ImageTextResult = try {
        val text = withTimeoutOrNull(TIMEOUT_MS) { recognize(file) }
        when {
            text == null -> ImageTextResult("", ImageTextStatus.FAILED)
            text.isBlank() -> ImageTextResult("", ImageTextStatus.EMPTY)
            else -> ImageTextResult(text, ImageTextStatus.EXTRACTED)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // OCR failure must not discard an image that a vision model can still use.
        ImageTextResult("", ImageTextStatus.FAILED)
    }

    private suspend fun recognize(file: File): String {
        slot.acquire()
        var bitmap: android.graphics.Bitmap? = null
        var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
        var taskOwnsResources = false
        try {
            currentCoroutineContext().ensureActive()
            val pixels = ImageAttachmentDecoder.decode(file).also { bitmap = it }
            currentCoroutineContext().ensureActive()
            val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
            return suspendCancellableCoroutine { continuation ->
                val task = client.process(InputImage.fromBitmap(pixels, 0))
                taskOwnsResources = true
                task.addOnCompleteListener(directExecutor) { completed ->
                    val result = runCatching {
                        if (!completed.isSuccessful) throw completed.exception ?: IllegalStateException("Text recognition failed")
                        PlainTextAttachment.cap(completed.result.text)
                    }
                    try { client.close() }
                    finally {
                        pixels.recycle()
                        slot.release()
                        result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                    }
                }
            }
        } finally {
            if (!taskOwnsResources) {
                try { recognizer?.close() }
                finally { bitmap?.recycle(); slot.release() }
            }
        }
    }
}
