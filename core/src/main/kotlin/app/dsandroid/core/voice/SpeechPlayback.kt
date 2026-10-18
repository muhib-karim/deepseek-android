package app.dsandroid.core.voice

/** Cursor and callback identity shared by pause, speed changes and asynchronous TTS completion. */
data class SpeechPlayback(
    val count: Int = 0,
    val position: Int = 0,
    val generation: Long = 0,
    val playing: Boolean = false,
    val paused: Boolean = false,
) {
    fun load(sentences: Int) = SpeechPlayback(count = sentences.coerceAtLeast(0), generation = generation + 1, paused = sentences > 0)
    fun resume(): SpeechPlayback = if (paused && position < count) copy(generation = generation + 1, playing = true, paused = false) else this
    fun pause(): SpeechPlayback = if (playing) copy(generation = generation + 1, playing = false, paused = true) else this
    fun stop() = SpeechPlayback(generation = generation + 1)
    fun finished(token: Long): SpeechPlayback = if (playing && token == generation)
        copy(position = position + 1, generation = generation + 1, playing = false, paused = position + 1 < count) else this
}
