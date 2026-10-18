package app.dsandroid.core.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechPlaybackTest {
    @Test fun `pause and resume repeat the interrupted sentence`() {
        val first = SpeechPlayback().load(3).resume()
        val second = first.finished(first.generation).resume()
        val resumed = second.pause().resume()
        assertEquals(1, resumed.position)
        assertTrue(resumed.playing)
        assertNotEquals(second.generation, resumed.generation)
    }
    @Test fun `completion from a stopped utterance cannot advance new playback`() {
        val old = SpeechPlayback().load(3).resume()
        val replacement = old.stop().load(2).resume()
        assertEquals(replacement, replacement.finished(old.generation))
    }
    @Test fun `duplicate completion cannot skip a sentence`() {
        val current = SpeechPlayback().load(3).resume()
        val next = current.finished(current.generation).resume()
        assertEquals(next, next.finished(current.generation))
        assertEquals(1, next.position)
    }
    @Test fun `last sentence completes once and cannot resume`() {
        val current = SpeechPlayback().load(1).resume()
        val done = current.finished(current.generation)
        assertFalse(done.playing)
        assertFalse(done.paused)
        assertEquals(done, done.resume())
        assertEquals(done, done.finished(current.generation))
    }
    @Test fun `completion while paused is ignored`() {
        val current = SpeechPlayback().load(2).resume()
        val paused = current.pause()
        assertEquals(paused, paused.finished(current.generation))
        assertEquals(0, paused.position)
    }
}
