package app.dsandroid.core.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetSummaryTest {
    @Test fun `current saved chat is used and private temporary chats are excluded`() {
        val chats = listOf(WidgetChat("saved", "Saved title", 1), WidgetChat("secret", "Temporary title", 2, temporary = true))
        assertEquals("Saved title", WidgetSummary.select(chats, "secret")?.title)
        assertNull(WidgetSummary.select(chats.drop(1), "secret"))
    }
    @Test fun `deletion falls back to newest saved chat and empty history clears widget`() {
        val chats = listOf(WidgetChat("old", "Older", 1), WidgetChat("new", "Newer", 2))
        assertEquals("Newer", WidgetSummary.select(chats, "deleted")?.title)
        assertNull(WidgetSummary.select(emptyList(), "deleted"))
    }
    @Test fun `rename refreshes title and long control laden titles are bounded`() {
        val chosen = WidgetSummary.select(listOf(WidgetChat("one", "New\n" + "x".repeat(200), 1)), "one")!!
        assertEquals(120, chosen.title.length)
        assertFalse(chosen.title.contains('\n'))
        assertTrue(chosen.title.startsWith("New "))
    }
}
