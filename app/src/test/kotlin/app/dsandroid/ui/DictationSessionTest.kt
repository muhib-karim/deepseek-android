package app.dsandroid.ui

import org.junit.Assert.*
import org.junit.Test

class DictationSessionTest {
    @Test fun lateReadyCannotRestartReleasedHoldButFinalResultIsAccepted() {
        val session = DictationSession()
        val generation = session.begin()
        assertTrue(session.canRecord(generation))
        session.release(generation)
        assertFalse(session.canRecord(generation))
        assertTrue(session.accepts(generation))
    }
    @Test fun cancellationAndNewPressInvalidateOldCallbacks() {
        val session = DictationSession()
        val old = session.begin()
        session.cancel()
        assertFalse(session.accepts(old))
        val current = session.begin()
        assertFalse(session.canRecord(old))
        assertTrue(session.canRecord(current))
        session.release(old)
        assertTrue(session.canRecord(current))
        session.cancel()
        assertFalse(session.canRecord(current))
    }
}
