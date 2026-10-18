package app.dsandroid.core.artifacts

import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.persistence.*
import app.dsandroid.core.tools.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ArtifactLibraryTest {
    @Test fun listsLegacyFilesVersionsAndImagesWithSearchAndSourceAndDeletesSharedFile() {
        val root = Files.createTempDirectory("library").toFile()
        try {
            val workspace = WorkspacePath(root)
            val artifact = ArtifactStore(workspace).create("html", "Site", "<h1>Hello</h1>")
            val edit = ArtifactStore(workspace).edit(artifact, "<h1>Updated</h1>")
            root.resolve("chart.png").writeBytes(byteArrayOf(1))
            val record = ConversationRecord("chat", "Demo", 1, emptyList(), listOf(StoredChatItem("a", "assistant", artifacts = listOf(artifact))))
            val library = ArtifactLibrary(workspace)
            val entries = library.list(listOf(record))
            assertEquals(3, entries.size)
            assertEquals("chat", entries.single { it.artifact.path == edit.path }.conversationId)
            assertEquals(1, ArtifactLibrary.filter(entries, "CHART", LibraryType.IMAGE).size)
            assertEquals(2, ArtifactLibrary.filter(entries, "demo", LibraryType.ARTIFACT).size)
            library.delete(artifact)
            assertFalse(root.resolve(artifact.path).exists())
            assertTrue(root.resolve(edit.path).exists())
            assertEquals(2, library.list(listOf(record)).size)
        } finally { root.deleteRecursively() }
    }
    @Test fun deletedFencedArtifactDoesNotReappearOnRefresh() {
        val root = Files.createTempDirectory("library-fences").toFile()
        try {
            val library = ArtifactLibrary(WorkspacePath(root))
            val record = ConversationRecord("chat", "Example", 1, emptyList(), listOf(StoredChatItem("a", "assistant",
                steps = listOf(app.dsandroid.core.agent.TurnStep.Answer("```html\n<h1>Hi</h1>\n```")))))
            val artifact = library.list(listOf(record)).single().artifact
            library.delete(artifact)
            assertTrue(library.isDeleted(artifact))
            assertTrue(library.list(listOf(record)).isEmpty())
            assertFalse(root.resolve(artifact.path).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun hidesInternalMetadataAndTemporaryOutputsAndRefusesTraversal() {
        val root = Files.createTempDirectory("library").toFile()
        try {
            val library = ArtifactLibrary(WorkspacePath(root))
            val file = Artifact("secret.txt", "text/plain", "secret.txt")
            root.resolve("secret.txt").writeText("temporary")
            library.record(file, "temporary", temporary = true)
            assertTrue(library.list(emptyList()).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { library.delete(Artifact("bad", "text/plain", "../bad")) }
        } finally { root.deleteRecursively() }
    }
}
