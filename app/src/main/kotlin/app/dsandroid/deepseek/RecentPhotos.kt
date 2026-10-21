package app.dsandroid.deepseek

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The attach panel's recent-photos strip (official layout). Reading the gallery needs the photos permission, which is asked
 * only when the person taps "Recent photos" in the panel; without it the Photo tile's system picker still works.
 */
object RecentPhotos {
    // The full build targets API 28 (it runs the bundled engine), where READ_EXTERNAL_STORAGE still grants photo access.
    val permission: String = if (Build.VERSION.SDK_INT >= 33 && app.dsandroid.BuildConfig.TARGET_SDK >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun allowed(context: Context): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The newest images, at most [limit]; empty when the gallery cannot be read. */
    suspend fun load(context: Context, limit: Int = 24): List<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val uris = mutableListOf<Uri>()
                while (uris.size < limit && cursor.moveToNext()) uris += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id))
                uris
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    @Suppress("DEPRECATION")
    suspend fun thumbnail(context: Context, uri: Uri, pixels: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(pixels, pixels), null)
            else MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, ContentUris.parseId(uri), MediaStore.Images.Thumbnails.MINI_KIND, null)
        }.getOrNull()
    }
}
