package app.dsandroid.core.voice

/** Session-only state: microphone use must never resume after process recreation. */
enum class VoicePhase { PAUSED, LISTENING, THINKING, SPEAKING }

data class VoiceSession(val phase: VoicePhase = VoicePhase.PAUSED, val generation: Long = 0) {
    fun listen() = copy(phase = VoicePhase.LISTENING, generation = generation + 1)
    fun pause() = copy(phase = VoicePhase.PAUSED, generation = generation + 1)
    fun heard(token: Long): VoiceSession = if (token == generation && phase == VoicePhase.LISTENING)
        copy(phase = VoicePhase.THINKING) else this
    fun answered(token: Long, success: Boolean): VoiceSession = if (token == generation && phase == VoicePhase.THINKING)
        if (success) copy(phase = VoicePhase.SPEAKING) else pause() else this
    fun finished(token: Long): VoiceSession = if (token == generation && phase == VoicePhase.SPEAKING) listen() else this
}
