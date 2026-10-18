package app.dsandroid.core.launch

import org.junit.Assert.*
import org.junit.Test

class QuickLaunchTest {
    @Test fun `known actions route only to new chat voice or camera`() {
        QuickAction.entries.forEach { assertEquals(it, QuickAction.fromAction(it.action)) }
        assertEquals(QuickAction.VOICE, QuickAction.fromAction("android.intent.action.ASSIST"))
        assertNull(QuickAction.fromAction("android.intent.action.SEND"))
        assertNull(QuickAction.fromAction("intent://untrusted"))
        assertNull(QuickAction.fromAction(null))
    }
    @Test fun `dynamic shortcuts respect capacity already occupied by static entries`() {
        assertEquals(2, QuickAction.dynamic(5, 3).size)
        assertEquals(3, QuickAction.dynamic(10, 3).size)
        assertTrue(QuickAction.dynamic(3, 3).isEmpty())
        assertTrue(QuickAction.dynamic(2, 3).isEmpty())
    }
}
