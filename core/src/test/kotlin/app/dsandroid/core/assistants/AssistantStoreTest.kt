package app.dsandroid.core.assistants

import app.dsandroid.core.llm.LlmJson
import app.dsandroid.core.personalization.ResponseStyle
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AssistantStoreTest {
    @Test fun createEditExportDelete() {
        val dir = Files.createTempDirectory("assistants").toFile()
        try {
            val store = AssistantStore(dir)
            val assistant = SavedAssistant("one", "Tutor", AssistantIcon.BOOK, "Ask questions", setOf("read_file"), ResponseStyle.EXPLANATORY)
            store.save(assistant)
            val snapshot = store.all().single()
            store.save(assistant.copy(name = "Coach"))
            assertEquals("Tutor", snapshot.name)
            assertEquals("Coach", AssistantStore(dir).all().single().name)
            assertEquals(assistant, LlmJson.decodeFromString<SavedAssistant>(store.export(snapshot)))
            store.delete("one")
            assertTrue(store.all().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsInvalidInput() {
        val dir = Files.createTempDirectory("assistants").toFile()
        try {
            val store = AssistantStore(dir)
            listOf(SavedAssistant("one", ""), SavedAssistant("../escape", "Tutor"),
                SavedAssistant("one", "Tutor", instructions = "x".repeat(8001)),
                SavedAssistant("one", "Tutor", defaultTools = setOf("bad tool"))).forEach {
                assertTrue(runCatching { store.save(it) }.isFailure)
            }
        } finally { dir.deleteRecursively() }
    }
}
