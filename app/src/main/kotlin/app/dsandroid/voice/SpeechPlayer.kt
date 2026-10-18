package app.dsandroid.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.dsandroid.core.voice.SpeechText
import app.dsandroid.core.voice.SpeechRange
import app.dsandroid.core.voice.SpeechPlayback

/** One owner at a time; only installed offline voices may receive answer text. */
class SpeechPlayer(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var disposed = false
    private val audio = context.getSystemService(AudioManager::class.java)
    private var hasFocus = false
    private var interrupted: () -> Unit = {}
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            if (change <= AudioManager.AUDIOFOCUS_LOSS && !disposed && speaking) { pause(); interrupted() }
        }, handler).build()
    private var ranges = emptyList<SpeechRange>()
    private var playback by mutableStateOf(SpeechPlayback())
    var rate by mutableStateOf(1f)
        private set
    val range: SpeechRange? get() = ranges.getOrNull(playback.position)
    val speaking: Boolean get() = playback.playing
    val paused: Boolean get() = playback.paused
    private var done: () -> Unit = {}
    private var failed: () -> Unit = {}
    var ready by mutableStateOf(false)
        private set
    var unavailable by mutableStateOf(false)
        private set
    var owner by mutableStateOf<String?>(null)
        private set
    var text by mutableStateOf("")
        private set

    init {
        try {
            engine = TextToSpeech(context.applicationContext) { status ->
                handler.post {
                    if (!disposed) {
                        ready = status == TextToSpeech.SUCCESS
                        unavailable = !ready
                    }
                }
            }
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { handler.post {
                    if (!disposed && utteranceId == playback.generation.toString() && speaking) {
                        playback = playback.finished(playback.generation)
                        if (playback.paused) speakCurrent() else { owner = null; releaseFocus(); done() }
                    }
                } }
                @Deprecated("Required by the platform listener")
                override fun onError(utteranceId: String?) { handler.post {
                    if (!disposed && utteranceId == playback.generation.toString() && speaking) { stop(); failed() }
                } }
            })
        } catch (_: Exception) { unavailable = true }
    }

    fun play(id: String, answer: String, onDone: () -> Unit = {}, onError: () -> Unit = {}, onInterrupted: () -> Unit = {}) {
        stop(); done = onDone; failed = onError; interrupted = onInterrupted
        if (!ready || answer.isBlank()) { failed(); return }
        owner = id; text = SpeechText.readableMarkdown(answer); ranges = SpeechText.sentences(text)
        playback = playback.load(ranges.size)
        if (ranges.isEmpty()) { stop(); onDone(); return }
        speakCurrent()
    }

    private fun speakCurrent() {
        try {
            val tts = requireNotNull(engine)
            val part = requireNotNull(range)
            val value = text.substring(part.start, part.end)
            val language = SpeechText.language(value)
            val voice = tts.voices?.filter {
                !it.isNetworkConnectionRequired && it.locale.language == language &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }?.sortedBy { it.name }?.firstOrNull()
            check(voice != null && tts.setVoice(voice) == TextToSpeech.SUCCESS)
            check(tts.setSpeechRate(rate) == TextToSpeech.SUCCESS)
            check(tts.setAudioAttributes(attributes) == TextToSpeech.SUCCESS)
            if (!hasFocus) hasFocus = audio?.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            check(hasFocus)
            playback = playback.resume()
            check(tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, playback.generation.toString()) != TextToSpeech.ERROR)
        } catch (_: Exception) { stop(); failed() }
    }

    // Android TTS has no pause primitive: resume repeats the interrupted sentence.
    fun pause() { if (speaking) { playback = playback.pause(); runCatching { engine?.stop() }; releaseFocus() } }
    fun resume() { if (paused && owner != null) speakCurrent() }
    fun changeRate(value: Float) {
        rate = SpeechText.speed(value)
        if (speaking) { pause(); speakCurrent() }
    }
    private fun releaseFocus() { if (hasFocus) { hasFocus = false; runCatching { audio?.abandonAudioFocusRequest(focus) } } }
    fun stop() { playback = playback.stop(); runCatching { engine?.stop() }; owner = null; releaseFocus() }
    fun close() { disposed = true; stop(); runCatching { engine?.shutdown() }; engine = null }
}
