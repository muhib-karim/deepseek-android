package app.dsandroid.core.llm

import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class ModelSelectorTest {
    @Test fun parsesOptionalFieldsAndPrefersNewestTimestamp() {
        val models = LlmJson.decodeFromString<ModelsResponse>("""{"data":[{"id":"deepseek-v4","created":10,"owned_by":"provider"},{"id":"deepseek-v3","created":20},{"id":"deepseek-v9"}]}""").data
        assertEquals("deepseek-v3", ModelSelector.pickDefault(models)?.id)
        assertEquals("provider", models.first().ownedBy)
    }
    @Test fun comparesNumericVersionsThenAlphabeticallyWithAliasesLast() {
        val ids = listOf("deepseek-chat", "deepseek-reasoner", "deepseek-v3.2", "deepseek-v4-pro", "deepseek-v4.1-flash")
        assertEquals("deepseek-v4.1-flash", ModelSelector.pickDefault(ids.map { ModelInfo(it) })?.id)
        assertEquals("v4.10", ModelSelector.pickDefault(listOf(ModelInfo("v4.9"), ModelInfo("v4.10")))?.id)
        assertEquals("a-v4", ModelSelector.pickDefault(listOf(ModelInfo("z-v4"), ModelInfo("a-v4")))?.id)
        assertEquals("deepseek-v3", ModelSelector.pickDefault(listOf(ModelInfo("deepseek-chat", 999), ModelInfo("deepseek-v3", 1)))?.id)
        assertNull(ModelSelector.pickDefault(emptyList()))
    }
    @Test fun capabilitiesAreHeuristicsWithExplicitOverrides() {
        assertTrue(ModelSelector.isReasoning("deepseek-r1"))
        assertTrue(ModelSelector.isReasoning("deepseek-reasoner"))
        assertFalse(ModelSelector.isVision("deepseek-v4"))
        assertTrue(ModelSelector.isVision("qwen2.5-vl"))
        assertTrue(ModelSelector.isVision("gpt-4o-mini"))
        assertFalse(ModelCapabilities(vision = false).isVision("gpt-4o"))
        assertTrue(ModelCapabilities(reasoning = true).isReasoning("custom"))
    }
    @Test fun remembersChoiceButAutomaticallySelectsNewestWithoutOne() {
        val models = listOf(ModelInfo("v1"), ModelInfo("v2"), ModelInfo("r1"))
        assertEquals("v2", ModelSelector.selected(models, ""))
        assertEquals("v1", ModelSelector.selected(models, "v1"))
        assertEquals("custom-id", ModelSelector.selected(emptyList(), "custom-id"))
        assertEquals(ModelSelector.OFFLINE_FALLBACK, ModelSelector.selected(emptyList(), ""))
        assertEquals("r1", ModelSelector.forTurn(models, "v1", true, emptyMap()))
        assertEquals("v1", ModelSelector.forTurn(models, "v1", false, emptyMap()))
        assertEquals("v1", ModelSelector.forTurn(models, "v1", true, mapOf("v1" to ModelCapabilities(reasoning = true))))
    }

    @Test fun deepseekTextModelsStayTextOnlyAndCustomVisionRequiresSupport() {
        listOf("deepseek-chat", "deepseek-reasoner", "deepseek-v3", "deepseek-r1").forEach {
            assertFalse(ModelCapabilities().isVision(it))
        }
        assertFalse(ModelCapabilities().isVision("private-model"))
        assertTrue(ModelCapabilities(vision = true).isVision("private-model"))
        assertFalse(ModelCapabilities(vision = false).isVision("qwen2.5-vl"))
    }
}
