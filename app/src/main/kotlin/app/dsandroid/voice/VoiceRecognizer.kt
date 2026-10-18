package app.dsandroid.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Main-thread, one-shot adapter; cancel invalidates even callbacks already queued by the service. */
class VoiceRecognizer(private val context: Context) {
    private var service: SpeechRecognizer? = null
    private var generation = 0L

    fun listen(language: String, result: (String) -> Unit, failed: () -> Unit) = start(language, result, failed, preferOnDevice = true)

    private fun start(language: String, result: (String) -> Unit, failed: () -> Unit, preferOnDevice: Boolean) {
        cancel()
        val token = generation
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                failed(); return
            }
            val onDevice = preferOnDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            check(onDevice || SpeechRecognizer.isRecognitionAvailable(context))
            val recognizer = if (onDevice && Build.VERSION.SDK_INT >= 31)
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
            service = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onError(error: Int) {
                    if (token != generation) return
                    if (onDevice && Build.VERSION.SDK_INT >= 31 &&
                        (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                        // The device service may exist without the requested language (for example Bangla).
                        // Retry once through the system service, whose possible network use is disclosed in voice UI.
                        start(language, result, failed, preferOnDevice = false)
                    } else { cancel(); failed() }
                }
                override fun onResults(results: Bundle?) {
                    if (token != generation) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                    cancel()
                    if (text.isBlank()) failed() else result(text)
                }
            })
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                if (language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (_: Exception) { cancel(); failed() }
    }

    fun cancel() {
        generation++
        runCatching { service?.cancel() }
        runCatching { service?.destroy() }
        service = null
    }
}
