package app.dsandroid.core.memory

import app.dsandroid.core.personalization.MemoryStore
import app.dsandroid.core.personalization.SavedFact
import app.dsandroid.core.llm.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MemoryUpkeepTest {
    @Test fun contextRemainsValidJsonAndExcludesToolsEvenAtTheLimit() {
        val chats = List(4) { listOf(ChatMessage("system", "instructions"), ChatMessage("tool", "tool secret"),
            ChatMessage("user", "\"quoted\"".repeat(4000)), ChatMessage("assistant", "Recent answer")) }
        val input = MemoryUpkeepContext.build(chats, List(100) { SavedFact("fact-$it", "x".repeat(2000)) })
        val json = LlmJson.parseToJsonElement(input).jsonObject
        assertTrue(input.length <= 60000)
        assertFalse(input.contains("tool secret"))
        assertFalse(input.contains("instructions"))
        assertTrue(json["conversations"]!!.jsonArray.any { it.jsonObject["text"]?.jsonPrimitive?.content == "Recent answer" })
    }
    @Test fun deletingAChatRemovesOnlyItsUnapprovedSuggestions() {
        val dir = Files.createTempDirectory("upkeep").toFile()
        try {
            val memory = MemoryStore(dir.resolve("facts")); val upkeep = MemoryUpkeepStore(dir.resolve("pending"), memory)
            memory.remember("Approved fact")
            upkeep.propose("first", """[{"kind":"ADD","text":"First proposal","reason":"Preference"}]""")
            upkeep.propose("second", """[{"kind":"ADD","text":"Second proposal","reason":"Preference"}]""")
            upkeep.removeChat("first")
            assertEquals("second", upkeep.all().single().chatId)
            assertEquals("Approved fact", memory.all().single().text)
        } finally { dir.deleteRecursively() }
    }
    @Test fun suggestionsNeverSaveWithoutApproval() {
        val dir = Files.createTempDirectory("upkeep").toFile()
        try {
            val memory = MemoryStore(dir.resolve("facts")); val upkeep = MemoryUpkeepStore(dir.resolve("pending"), memory)
            upkeep.propose("chat", """[{"kind":"ADD","text":"Prefers short answers","reason":"Repeated preference"}]""")
            assertTrue(memory.all().isEmpty())
            upkeep.reject(upkeep.all().single().id)
            assertTrue(memory.all().isEmpty())
            upkeep.propose("chat", """[{"kind":"ADD","text":"Prefers short answers","reason":"Repeated preference"}]""")
            upkeep.approve(upkeep.all().single().id)
            assertEquals("Prefers short answers", memory.all().single().text)
            assertTrue(upkeep.all().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun staleEditsAndDeletesCannotOverwriteNewFacts() {
        val dir = Files.createTempDirectory("upkeep").toFile()
        try {
            val memory = MemoryStore(dir.resolve("facts")); val upkeep = MemoryUpkeepStore(dir.resolve("pending"), memory)
            val fact = memory.remember("Old preference")
            upkeep.propose("chat", """[{"kind":"EDIT","targetId":"${fact.id}","text":"New preference","reason":"Correction"}]""")
            memory.edit(fact.id, "Manual correction")
            assertTrue(runCatching { upkeep.approve(upkeep.all().single().id) }.isFailure)
            assertEquals("Manual correction", memory.all().single().text)
            upkeep.reject(upkeep.all().single().id)
            upkeep.propose("chat", """[{"kind":"DELETE","targetId":"${fact.id}","reason":"No longer relevant"}]""")
            upkeep.approve(upkeep.all().single().id)
            assertTrue(memory.all().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun secretsAndUnknownTargetsAreRejected() {
        val dir = Files.createTempDirectory("upkeep").toFile()
        try {
            val upkeep = MemoryUpkeepStore(dir.resolve("pending"), MemoryStore(dir.resolve("facts")))
            assertTrue(runCatching { upkeep.propose("chat", """[{"kind":"EDIT","targetId":"missing","text":"x","reason":"x"}]""") }.isFailure)
            assertTrue(runCatching { upkeep.propose("chat", """[{"kind":"ADD","text":"password: fixture","reason":"x"}]""") }.isFailure)
            assertTrue(upkeep.all().isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
