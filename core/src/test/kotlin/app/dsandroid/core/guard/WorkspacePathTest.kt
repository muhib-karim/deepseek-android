package app.dsandroid.core.guard

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspacePathTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun confinesPathsAndRejectsTraversalVariants() {
        val root = temporary.newFolder("workspace")
        val paths = WorkspacePath(root)
        assertEquals(File(root, "note.md").canonicalFile, paths.resolve("note.md"))
        listOf("../escape", "a/../../escape", "a\\..\\escape", "%2e%2e/escape", "~/escape", "file:escape", "bad\u0000name").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { paths.resolve(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { paths.resolve(temporary.newFolder("outside").path) }
        assertThrows(IllegalArgumentException::class.java) { paths.resolve(root.path + "-sibling/file") }
    }
    @Test fun rejectsSymlinkAncestorsEvenForNonexistentChildren() {
        val root = temporary.newFolder("workspace")
        val outside = temporary.newFolder("outside")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { WorkspacePath(root).resolve("link/new.txt") }
    }
}
