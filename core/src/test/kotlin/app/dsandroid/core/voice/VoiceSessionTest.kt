package app.dsandroid.core.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceSessionTest {
    @Test fun `spoken conversation repeats only after answer playback completes`() {
        val listening = VoiceSession().listen()
        val thinking = listening.heard(listening.generation)
        assertEquals(VoicePhase.THINKING, thinking.phase)
        val speaking = thinking.answered(thinking.generation, true)
        assertEquals(VoicePhase.SPEAKING, speaking.phase)
        val next = speaking.finished(speaking.generation)
        assertEquals(VoicePhase.LISTENING, next.phase)
        assertTrue(next.generation > listening.generation)
    }
    @Test fun `tap interruption invalidates speech and recognition callbacks`() {
        val old = VoiceSession().listen()
        val current = old.pause().listen()
        assertEquals(current, current.heard(old.generation))
        assertEquals(current, current.finished(old.generation))
    }
    @Test fun `failed turn pauses instead of repeatedly sending`() {
        val thinking = VoiceSession().listen().let { it.heard(it.generation) }
        assertEquals(VoicePhase.PAUSED, thinking.answered(thinking.generation, false).phase)
    }
    @Test fun `duplicate recognition cannot submit twice`() {
        val listening = VoiceSession().listen()
        val thinking = listening.heard(listening.generation)
        assertEquals(thinking, thinking.heard(listening.generation))
        assertEquals(thinking, thinking.finished(listening.generation))
    }
    @Test fun `background pause cannot restart listening from a late answer`() {
        val thinking = VoiceSession().listen().let { it.heard(it.generation) }
        val paused = thinking.pause()
        assertEquals(paused, paused.answered(thinking.generation, true))
    }
}
