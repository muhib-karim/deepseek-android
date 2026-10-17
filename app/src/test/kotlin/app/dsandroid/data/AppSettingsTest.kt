package app.dsandroid.data

import org.junit.Assert.*
import org.junit.Test

class AppSettingsTest {
    @Test fun runIdentityUsesOpaqueBindingAndEndpointWithoutCredentials() {
        val settings = AppSettings(accountBinding = "opaque-binding", apiKey = "fixture-key")
        assertEquals("API_KEY:https://api.deepseek.com:opaque-binding", settings.runIdentity)
        assertFalse(settings.runIdentity.contains(settings.apiKey))
        assertNotEquals(settings.runIdentity, settings.copy(accountBinding = "replacement-binding").runIdentity)
    }
    @Test fun displayAndSearchDefaultsAreHonest() {
        val settings = AppSettings()
        assertEquals(Appearance.SYSTEM, settings.appearance)
        assertEquals(1f, settings.fontScale)
        assertTrue(settings.collapseThinking)
        assertEquals("duckduckgo", settings.searchBackend)
        assertFalse(settings.accountConfigured)
    }

    @Test fun displayChoicesSurviveSettingsCopies() {
        val settings = AppSettings(appearance = Appearance.DARK, fontScale = 1.2f,
            collapseThinking = false, audioLanguage = "en-US", searchBackend = "searxng",
            searxngUrl = "https://search.example.org")
        val changed = settings.copy(model = "chosen-model")
        assertEquals(settings.appearance, changed.appearance)
        assertEquals(settings.fontScale, changed.fontScale)
        assertEquals(settings.searxngUrl, changed.searxngUrl)
    }
}
