package app.dsandroid.e2e

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File

/**
 * Test-only share target ("E2E receiver"): keeps the one file another app shares, with its type, under files/received/,
 * so the journey can check the bytes that reached a second app through the system share sheet and its read grant.
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val stream = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (stream != null) runCatching {
            val name = (stream.lastPathSegment ?: "shared").substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val directory = File(filesDir, "received").apply { mkdirs() }
            contentResolver.openInputStream(stream)?.use { input -> File(directory, name).outputStream().use { input.copyTo(it) } }
            File(directory, "$name.type").writeText(intent.type.orEmpty())
        }
        finish()
    }
}
