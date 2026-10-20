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

    @Test fun moreChatsThanBeforeAreKeptForTheServerList() {
        val directory = Files.createTempDirectory("many-history").toFile()
        try {
            val store = WebHistory(directory.resolve("chats.json"))
            val chats = (1..150).map { WebChat("c$it", "Chat $it", "s$it", remote = true, updatedAt = it.toLong()) }
            store.save(chats)
            assertEquals(chats, store.load())
        } finally { directory.deleteRecursively() }
    }

    @Test fun filesFeedbackAndTimelineSurviveRestart() {
        val directory = Files.createTempDirectory("rich-history").toFile()
        try {
            val store = WebHistory(directory.resolve("chats.json"))
            val user = WebMessage("u", true, "Read this", files = listOf(WebAttachment("notes.txt", 1200, "file-1")))
            val reply = WebMessage("a", false, "Answer[citation:1]", feedback = "GOOD",
                citations = listOf(WebCitation("Page", "https://example.org", "Example", "https://cdn.deepseek.com/site-icons/example.org")),
                timeline = listOf(WebStep.Think("Plan", 1.5), WebStep.Search("Found 1 web pages", listOf("q"), listOf(WebCitation("Page", "https://example.org"))),
                    WebStep.Read(listOf(WebCitation("Page", "https://example.org"))), WebStep.Tool("Found keyword")))
            val chat = WebChat("local", "Title", "server", 2, listOf(user, reply), syncedAt = 10, remoteUpdatedAt = 9)
            store.save(listOf(chat))
            assertEquals(chat, store.load().single())
        } finally { directory.deleteRecursively() }
    }

    @Test fun overTheSizeLimitOldChatsDeepSeekKeepsReloadLaterAndLocalOnlyHistoryStays() {
        val directory = Files.createTempDirectory("full-history").toFile()
        try {
            val store = WebHistory(directory.resolve("chats.json"))
            val big = "x".repeat(3 * 1024 * 1024)
            val oldSynced = WebChat("old", "Old", "s-old", 4, listOf(WebMessage("a", false, big)), updatedAt = 1)
            val localOnly = WebChat("mine", "Mine", null, null, listOf(WebMessage("b", false, big)), updatedAt = 2)
            val agent = WebChat("agent", "Agent", "s-agent", 5, listOf(WebMessage("c", true, big, agent = true)), updatedAt = 0)
            store.save(listOf(oldSynced, localOnly, agent))
            val loaded = store.load().associateBy { it.id }
            assertTrue(loaded.getValue("old").remote)
            assertTrue(loaded.getValue("old").messages.isEmpty())
            assertEquals(localOnly, loaded.getValue("mine"))
            assertEquals(agent, loaded.getValue("agent"))
            assertThrows(IllegalArgumentException::class.java) { store.save(listOf(localOnly, localOnly.copy(id = "mine-2"), localOnly.copy(id = "mine-3"))) }
        } finally { directory.deleteRecursively() }
    }
}
