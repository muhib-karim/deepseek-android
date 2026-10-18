package app.dsandroid.deepseek

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.dsandroid.core.tools.Artifact
import java.io.File

/** Open, share and save generated files. Only the system's own UI receives a read grant for one file. */
object ArtifactActions {
    private fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.artifacts", file)

    fun share(context: Context, artifact: Artifact, file: File) {
        val uri = uri(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = artifact.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(artifact.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${artifact.fileName}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun openExternal(context: Context, artifact: Artifact, file: File) {
        val uri = uri(context, file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, artifact.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(artifact.fileName, uri) }
        context.startActivity(Intent.createChooser(intent, "Open ${artifact.fileName}"))
    }

    /** Android 10+: MediaStore Downloads/DeepSeek, no permission. Returns false where the caller must use the system file picker. */
    fun saveToDownloads(context: Context, artifact: Artifact, file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, artifact.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, artifact.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DeepSeek")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Downloads is unavailable")
        try {
            resolver.openOutputStream(target)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: throw IllegalStateException("Downloads is unavailable")
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            resolver.delete(target, null, null)
            throw error
        }
        return true
    }

    /** Older Android: copy into the document the user created with the system file picker. */
    fun copyTo(context: Context, file: File, target: Uri) {
        context.contentResolver.openOutputStream(target)?.use { output -> file.inputStream().use { it.copyTo(output) } }
            ?: throw IllegalStateException("The destination could not be opened")
    }
}
