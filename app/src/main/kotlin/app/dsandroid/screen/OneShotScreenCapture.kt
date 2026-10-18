package app.dsandroid.screen

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.dsandroid.core.screen.CaptureScope
import app.dsandroid.core.screen.ScreenCapturePolicy
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Never a service. Every invocation owns one consent result, display, reader and bitmap. */
object OneShotScreenCapture {
    suspend fun capture(activity: Activity, consentCode: Int = Activity.RESULT_CANCELED, consent: Intent? = null): File {
        check((activity as? LifecycleOwner)?.lifecycle?.currentState == Lifecycle.State.RESUMED)
        var bitmap: Bitmap? = null
        var output: File? = null
        try {
            withTimeout(5000) {
                if (ScreenCapturePolicy.scope(Build.VERSION.SDK_INT) == CaptureScope.OWN_WINDOW) {
                    window(activity) { bitmap = it }
                } else {
                    require(consentCode == Activity.RESULT_OK && consent != null)
                    projection(activity, consentCode, consent) { bitmap = it }
                }
            }
            check((activity as? LifecycleOwner)?.lifecycle?.currentState == Lifecycle.State.RESUMED)
            withContext(Dispatchers.IO) {
                val directory = File(activity.cacheDir, "camera").apply { check(isDirectory || mkdirs()) }
                output = File(directory, "${UUID.randomUUID()}.png")
                output!!.outputStream().use { check(requireNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            return requireNotNull(output)
        } catch (error: Exception) { output?.delete(); throw error }
        finally { bitmap?.recycle() }
    }

    private suspend fun window(activity: Activity, receive: (Bitmap) -> Unit) = suspendCancellableCoroutine<Unit> { continuation ->
        val decor = activity.window.decorView
        val size = ScreenCapturePolicy.size(decor.width, decor.height)
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        // PixelCopy may still be writing after cancellation; recycle only in its callback.
        try {
            PixelCopy.request(activity.window, bitmap, { result ->
                if (continuation.isActive && result == PixelCopy.SUCCESS) { receive(bitmap); continuation.resume(Unit) }
                else {
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Screen copy failed"))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (error: Exception) { bitmap.recycle(); continuation.resumeWithException(error) }
    }

    @Suppress("DEPRECATION")
    private suspend fun projection(activity: Activity, code: Int, consent: Intent, receive: (Bitmap) -> Unit) = suspendCancellableCoroutine<Unit> { continuation ->
        val handler = Handler(Looper.getMainLooper())
        var projection: MediaProjection? = null
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var closed = false
        fun release() {
            if (closed) return
            closed = true
            reader?.setOnImageAvailableListener(null, null)
            display?.release(); reader?.close(); projection?.stop()
        }
        continuation.invokeOnCancellation { handler.post { release() } }
        try {
            check(Build.VERSION.SDK_INT in 26..28)
            val metrics = android.util.DisplayMetrics()
            activity.windowManager.defaultDisplay.getRealMetrics(metrics)
            val size = ScreenCapturePolicy.size(metrics.widthPixels, metrics.heightPixels)
            projection = requireNotNull(activity.getSystemService(MediaProjectionManager::class.java)).getMediaProjection(code, consent)
            requireNotNull(projection).registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    release()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Screen consent ended"))
                }
            }, handler)
            reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
            reader!!.setOnImageAvailableListener({ source ->
                if (closed) return@setOnImageAvailableListener
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                var padded: Bitmap? = null
                var cropped: Bitmap? = null
                var transferred: Bitmap? = null
                try {
                    image.use {
                        val plane = it.planes[0]
                        require(plane.pixelStride == 4 && plane.rowStride % 4 == 0)
                        val paddedWidth = plane.rowStride / 4
                        require(paddedWidth in size.width..(size.width + 1024))
                        padded = Bitmap.createBitmap(paddedWidth, size.height, Bitmap.Config.ARGB_8888)
                        padded!!.copyPixelsFromBuffer(plane.buffer)
                        cropped = Bitmap.createBitmap(padded!!, 0, 0, size.width, size.height)
                    }
                    if (continuation.isActive) { receive(requireNotNull(cropped)); transferred = cropped; continuation.resume(Unit) }
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                finally {
                    // createBitmap can return its source when no crop is needed.
                    if (padded !== transferred) padded?.recycle()
                    if (cropped !== padded && cropped !== transferred) cropped?.recycle()
                    release()
                }
            }, handler)
            display = projection!!.createVirtualDisplay(activity.getString(app.dsandroid.R.string.app_name), size.width, size.height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        } catch (error: Exception) {
            release()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}
