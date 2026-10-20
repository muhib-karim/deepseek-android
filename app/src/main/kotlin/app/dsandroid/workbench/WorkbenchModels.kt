package app.dsandroid.workbench

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContract
import app.dsandroid.core.editor.EditorLanguage
import app.dsandroid.core.workbench.WorkbenchArtifact

/** What the workbench pane shows next to (or over) the chat. */
sealed interface WorkbenchPane {
    /** An artifact with its versions; [revising] while DeepSeek works on the next one. */
    data class Panel(val artifact: WorkbenchArtifact, val revising: Boolean = false) : WorkbenchPane
    data class Editor(val file: EditorFile) : WorkbenchPane
}

/** A text buffer in the editor. [path] is workspace-relative once saved; null for a code block not yet saved. */
data class EditorFile(val key: String, val name: String, val text: String, val language: EditorLanguage, val path: String?, val chatId: String)

/** ACTION_CREATE_DOCUMENT with a file name and MIME type chosen per call; needs no storage permission. */
class CreateTextDocument : ActivityResultContract<CreateTextDocument.Request, Uri?>() {
    data class Request(val name: String, val mimeType: String)

    override fun createIntent(context: Context, input: Request): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.mimeType).putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

object TextFiles {
    /** Android's own type for the extension, so document providers keep the chosen name. */
    fun mimeType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    fun writeTo(context: Context, target: Uri, text: String) {
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("The destination could not be opened")
    }

    /** Shares the text itself through the system sheet; very large buffers must be saved first. */
    fun share(context: Context, name: String, text: String): Boolean {
        if (text.length > MAX_SHARE_CHARS) return false
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, name)
        context.startActivity(Intent.createChooser(intent, "Share $name"))
        return true
    }

    const val MAX_SHARE_CHARS = 100_000
}
