package app.dsandroid.core.deepseek

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class WebHistoryTest {
    @Test fun retainsTheServerParentAcrossRestartsAndClearsOnLogout() {
        val directory = Files.createTempDirectory("web-history").toFile()
        try {
            val file = directory.resolve("chats.json")
            val chat = WebChat("local", "Title", "server", 23, listOf(WebMessage("u", true, "Hello"), WebMessage("a", false, "World", "Reason")))
            WebHistory(file).save(listOf(chat))
            assertEquals(listOf(chat), WebHistory(file).load())
            WebHistory(file).clear()
            assertTrue(WebHistory(file).load().isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun interruptedTurnRemainsBlockedAfterRestart() {
        val directory = Files.createTempDirectory("web-history").toFile()
        try {
            val store = WebHistory(directory.resolve("chats.json"))
            store.save(listOf(WebChat("local", "Title", blocked = true)))
            assertTrue(store.load().single().blocked)
        } finally { directory.deleteRecursively() }
    }
}
