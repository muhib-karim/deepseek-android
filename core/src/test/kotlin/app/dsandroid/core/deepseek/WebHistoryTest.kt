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
    @Test fun branchingMetadataAndAttachmentReferencesSurviveRestart() {
        val directory = Files.createTempDirectory("branch-history").toFile()
        try {
            val store = WebHistory(directory.resolve("chats.json"))
            val user = WebMessage("u", true, "Edited", serverId = 21, parentId = 20, prompt = "Edited",
                thinkingEnabled = true, searchEnabled = true, refFileIds = listOf("file-1"))
            val assistant = WebMessage("a", false, "Answer", "Thinking", serverId = 22, parentId = 20, prompt = "Edited",
                thinkingEnabled = true, searchEnabled = true, refFileIds = listOf("file-1"))
            val chat = WebChat("local", "Title", "server", 22, listOf(user, assistant))
            store.save(listOf(chat))
            assertEquals(chat, store.load().single())
        } finally { directory.deleteRecursively() }
    }

    @Test fun oldHistoryLoadsWithSafeDefaultBranchingMetadata() {
        val directory = Files.createTempDirectory("legacy-history").toFile()
        try {
            val file = directory.resolve("chats.json")
            file.writeText("""[{"id":"old","title":"Title","messages":[{"id":"u","user":true,"text":"Hello"}]}]""")
            val message = WebHistory(file).load().single().messages.single()
            assertNull(message.serverId)
            assertNull(message.parentId)
            assertNull(message.prompt)
            assertFalse(message.thinkingEnabled)
            assertFalse(message.searchEnabled)
            assertTrue(message.refFileIds.isEmpty())
        } finally { directory.deleteRecursively() }
    }

}
