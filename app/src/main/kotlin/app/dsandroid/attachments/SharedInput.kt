package app.dsandroid.attachments

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import app.dsandroid.core.attachments.SharedContentPolicy

/** Only content grants are imported. Neither URLs nor text are ever opened or sent automatically. */
data class SharedInput(val text: String, val uris: List<Uri>, val limited: Boolean) {
    companion object {
        fun read(intent: Intent, packageName: String): SharedInput? {
            if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
            return runCatching {
                val rawText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().take(51)
                } else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                val clip = intent.clipData
                val candidates = (streams + (0 until minOf(clip?.itemCount ?: 0, 51)).mapNotNull { clip?.getItemAt(it)?.uri }).distinct()
                val accepted = SharedContentPolicy.uris(candidates.map { it.toString() }, "$packageName.artifacts").map(Uri::parse)
                SharedInput(rawText.take(SharedContentPolicy.MAX_TEXT), accepted,
                    rawText.length > SharedContentPolicy.MAX_TEXT || candidates.size > accepted.size || (clip?.itemCount ?: 0) > 50)
            }.getOrElse { SharedInput("", emptyList(), true) }
        }
    }
}
