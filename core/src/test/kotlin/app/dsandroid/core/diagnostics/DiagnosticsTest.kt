package app.dsandroid.core.diagnostics

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @Test fun redactsBeforePersistingAndAfterReopen() {
        val root = Files.createTempDirectory("diagnostics-test").toFile()
        try {
            val store = DiagnosticStore(root) { 42L }
            val samples = listOf("Authorization: Bearer fixture-credential", "token=fixture-token", "api_key=fixture-api",
                "cookie: session=fixture-cookie", "mail contact@example.invalid", "sk-fixture-secret",
                "eyJhbGciOiJub25lIn0.eyJzdWIiOiJmaXh0dXJlIn0.fixture", "password=fixture-password", "Authorization: Bearer\nfixture-folded", "\"Cookie\":\"session=fixture-json-cookie\"", "client_secret=fixture-client")
            samples.forEach { store.append(DiagnosticKind.ERROR, it) }
            val disk = root.listFiles()!!.joinToString { it.readText() }
            listOf("fixture-credential", "fixture-token", "fixture-api", "fixture-cookie", "contact@example.invalid", "sk-fixture-secret", "eyJhbGci", "fixture-password", "fixture-folded", "fixture-json-cookie", "fixture-client").forEach { assertFalse(disk.contains(it)) }
            assertEquals(11, DiagnosticStore(root).entries().size)
            assertEquals(42L, store.entries().first().timestamp)
        } finally { root.deleteRecursively() }
    }
    @Test fun evictsOldestEntriesAcrossInstancesAndBoundsMessages() {
        val root = Files.createTempDirectory("diagnostics-test").toFile()
        try {
            val first = DiagnosticStore(root) { 1L }; val second = DiagnosticStore(root) { 2L }
            repeat(205) { (if (it % 2 == 0) first else second).append(DiagnosticKind.TOOL_FAILURE, "failure $it") }
            val values = first.entries()
            assertEquals(200, values.size)
            assertEquals("failure 5", values.first().message)
            assertEquals("failure 204", values.last().message)
            first.append(DiagnosticKind.ERROR, "word ".repeat(2000))
            assertTrue(first.entries().last().message.length <= 2000)
        } finally { root.deleteRecursively() }
    }
    @Test fun clearIsDurable() {
        val root = Files.createTempDirectory("diagnostics-test").toFile()
        try {
            DiagnosticStore(root).append(DiagnosticKind.ERROR, "failure")
            DiagnosticStore(root).clear()
            assertTrue(DiagnosticStore(root).entries().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun scrubsKnownCredentialsWithoutLoggingRawContext() {
        val root = Files.createTempDirectory("diagnostics-test").toFile()
        try {
            val store = DiagnosticStore(root)
            store.append(DiagnosticKind.ERROR, "failed opaque-fixture-value", setOf("opaque-fixture-value"))
            assertEquals("failed [redacted]", store.entries().single().message)
        } finally { root.deleteRecursively() }
    }
}
