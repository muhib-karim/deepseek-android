package app.dsandroid.data

import app.dsandroid.core.backup.*
import app.dsandroid.core.accounts.*
import org.junit.Assert.*
import org.junit.Test

class BackupSettingsTest {
    @Test fun exportAllowlistCannotIncludeCredentialsOrEndpoints() {
        val value = AppSettings(apiKey = "fixture-api", customKey = "fixture-custom", accountToken = "fixture-account",
            baseUrl = "https://example.invalid/private-endpoint", accounts = listOf(AccountProfile("id", "Profile", AccountKind.API_KEY, "fixture-profile")))
        val encoded = BackupCodec.encode(BackupArchive(settings = value.backupSettings("bn")))
        listOf("fixture-api", "fixture-custom", "fixture-account", "fixture-profile", "private-endpoint", "apiKey", "accountToken").forEach { assertFalse(encoded.contains(it)) }
    }
    @Test fun restoreChangesOnlyAllowlistedSettingsAndKeepsSelectedAccount() {
        val original = AppSettings(apiKey = "fixture-api", activeAccountId = "original", baseUrl = "https://example.invalid")
        val result = original.withBackupSettings(BackupSettings(appearance = "DARK", customInstructions = "Use short answers"))
        assertEquals(Appearance.DARK, result.appearance)
        assertEquals("Use short answers", result.customInstructions)
        assertEquals(original.apiKey, result.apiKey)
        assertEquals(original.baseUrl, result.baseUrl)
        assertEquals("original", result.activeAccountId)
    }
}
