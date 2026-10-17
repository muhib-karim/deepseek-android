package app.dsandroid.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.dsandroid.R

/** The system service owns recognition; the app never records or retains audio. */
@Composable
fun VoiceDictation(enabled: Boolean, language: String, append: (String) -> Unit, report: (String) -> Unit) {
    val context = LocalContext.current
    val latestAppend by rememberUpdatedState(append)
    val latestReport by rememberUpdatedState(report)
    var listening by remember { mutableStateOf(false) }
    val session = remember { DictationSession() }
    var activeGeneration by remember { mutableStateOf(0L) }
    val latestEnabled by rememberUpdatedState(enabled)
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val unavailable = stringResource(R.string.voice_unavailable)
    val denied = stringResource(R.string.voice_permission)
    val failed = stringResource(R.string.voice_failed)
    val label = stringResource(if (listening) R.string.listening else R.string.hold_dictate)
    fun start(): Long {
        if(!latestEnabled) return 0L
        if(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { latestReport(denied); return 0L }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { latestReport(unavailable); return 0L }
        try {
            session.cancel()
            recognizer?.cancel(); recognizer?.destroy()
            val token = session.begin()
            activeGeneration = token
            val service = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
            service.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if(session.canRecord(token) && latestEnabled) listening = true
                    else { if(session.accepts(token)) { session.cancel(); listening = false }; service.cancel() }
                }
                override fun onBeginningOfSpeech() { if(!session.canRecord(token) || !latestEnabled) { if(session.accepts(token)) { session.cancel(); listening = false }; service.cancel() } }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if(session.accepts(token)) listening = false }
                override fun onError(error: Int) { if(session.accepts(token)) { session.cancel(); listening = false; if(error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) latestReport(failed) } }
                override fun onResults(results: Bundle?) { if(session.accepts(token) && latestEnabled) { session.cancel(); listening = false; results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(latestAppend) } }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            service.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                if(language.isNotEmpty()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
            listening = true
            return token
        } catch (_: Exception) { session.cancel(); recognizer?.cancel(); listening = false; latestReport(unavailable); return 0L }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // A permission sheet ends the original hold; start on the next deliberate hold.
        if(!granted) latestReport(denied)
    }
    DisposableEffect(Unit) { onDispose { session.cancel(); recognizer?.cancel(); recognizer?.destroy() } }
    LaunchedEffect(enabled) { if(!enabled) { session.cancel(); recognizer?.cancel(); listening = false } }
    Surface(color = if(listening) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.size(44.dp).semantics { contentDescription = label; role = Role.Button; onClick { if(enabled) { if(listening) { session.release(activeGeneration); recognizer?.stopListening(); listening = false } else if(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start() else permission.launch(Manifest.permission.RECORD_AUDIO) }; true } }
            .pointerInput(enabled, language) {
                detectTapGestures(onPress = {
                    if(enabled) {
                        var pressToken = 0L
                        if(!SpeechRecognizer.isRecognitionAvailable(context)) latestReport(unavailable)
                        else if(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pressToken = start()
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                        val released = try { tryAwaitRelease() } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            if(session.accepts(pressToken)) { session.cancel(); recognizer?.cancel(); listening = false }
                            throw cancelled
                        }
                        if(session.accepts(pressToken)) {
                            if(released) { session.release(pressToken); recognizer?.stopListening() }
                            else { session.cancel(); recognizer?.cancel() }
                            listening = false
                        }
                    }
                })
            }) { androidx.compose.foundation.layout.Box(contentAlignment = androidx.compose.ui.Alignment.Center) { AppIcon(Glyph.MIC) } }
}
