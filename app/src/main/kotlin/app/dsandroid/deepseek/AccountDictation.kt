package app.dsandroid.deepseek

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.dsandroid.ui.AppIcon
import app.dsandroid.ui.DictationSession
import app.dsandroid.ui.Glyph
import app.dsandroid.voice.VoiceRecognizer

/** Injectable speech boundary; the production adapter uses the existing system recognizer. */
interface AccountSpeechInput {
    fun listen(result: (String) -> Unit, failed: () -> Unit)
    fun cancel()
}

@Composable
fun AccountDictation(
    enabled: Boolean,
    append: (String) -> Unit,
    report: (String) -> Unit,
    input: AccountSpeechInput? = null,
    trigger: Int = 0,
    onTriggerConsumed: () -> Unit = {},
    /** Settings > Audio > Main language (a BCP 47 tag); empty uses the recognizer's default. */
    language: String = "",
) {
    val context = LocalContext.current
    val latestLanguage by rememberUpdatedState(language)
    val system = remember(context) {
        val recognizer = VoiceRecognizer(context)
        object : AccountSpeechInput {
            override fun listen(result: (String) -> Unit, failed: () -> Unit) = recognizer.listen(latestLanguage, result, failed)
            override fun cancel() = recognizer.cancel()
        }
    }
    val speech = input ?: system
    val session = remember { DictationSession() }
    var listening by remember { mutableStateOf(false) }
    val latestAppend by rememberUpdatedState(append)
    val latestReport by rememberUpdatedState(report)
    val latestEnabled by rememberUpdatedState(enabled)
    fun cancel() { session.cancel(); speech.cancel(); listening = false }
    fun start() {
        if (!latestEnabled) return
        cancel()
        val token = session.begin()
        listening = true
        try {
            speech.listen(result = { text ->
                if (session.accepts(token) && latestEnabled) {
                    cancel()
                    if (text.isNotBlank()) latestAppend(text.trim())
                }
            }, failed = {
                if (session.accepts(token)) {
                    cancel()
                    latestReport("Speech recognition is unavailable. Check your device's speech service or type your message.")
                }
            })
        } catch (_: Exception) {
            cancel()
            latestReport("Speech recognition is unavailable. Check your device's speech service or type your message.")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else latestReport("Microphone permission was denied. Allow microphone access to dictate, or type your message.")
    }
    fun request() {
        if (!latestEnabled) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(enabled) { if (!enabled) cancel() }
    LaunchedEffect(trigger) { if (trigger > 0) { onTriggerConsumed(); request() } }
    DisposableEffect(speech) { onDispose { session.cancel(); speech.cancel() } }
    IconButton(onClick = { if (listening) cancel() else request() }, enabled = enabled) {
        AppIcon(if (listening) Glyph.STOP else Glyph.MIC, if (listening) "Stop dictation" else "Dictate message")
    }
}
