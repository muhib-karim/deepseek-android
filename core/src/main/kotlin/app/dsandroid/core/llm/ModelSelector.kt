package app.dsandroid.core.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class ModelsResponse(val data: List<ModelInfo> = emptyList())

@Serializable
data class ModelInfo(val id: String, val created: Long? = null, @SerialName("owned_by") val ownedBy: String? = null)

@Serializable
data class ModelCapabilities(val vision: Boolean? = null, val reasoning: Boolean? = null) {
    fun isVision(id: String): Boolean = vision ?: ModelSelector.isVision(id)
    fun isReasoning(id: String): Boolean = reasoning ?: ModelSelector.isReasoning(id)
}

object ModelSelector {
    const val OFFLINE_FALLBACK = "deepseek-chat"
    private val aliases = setOf("deepseek-chat", "deepseek-reasoner", "chat", "reasoner", "latest")
    private fun version(id: String): List<Long> = Regex("(?:^|[-_])v?(\\d+(?:\\.\\d+)*)", RegexOption.IGNORE_CASE)
        .find(id)?.groupValues?.get(1)?.split('.')?.map { it.toLongOrNull() ?: 0L }.orEmpty()

    fun ranked(models: List<ModelInfo>): List<ModelInfo> = models.filter { it.id.isNotBlank() }.distinctBy { it.id }.sortedWith { a, b ->
        val aliasOrder = (a.id.lowercase(Locale.ROOT) in aliases).compareTo(b.id.lowercase(Locale.ROOT) in aliases)
        if (aliasOrder != 0) return@sortedWith aliasOrder
        if (a.created != null || b.created != null) {
            val dateOrder = compareValues(b.created, a.created)
            if (dateOrder != 0) return@sortedWith dateOrder
        }
        val av = version(a.id); val bv = version(b.id)
        for (index in 0 until maxOf(av.size, bv.size)) {
            val order = (bv.getOrElse(index) { 0L }).compareTo(av.getOrElse(index) { 0L })
            if (order != 0) return@sortedWith order
        }
        a.id.compareTo(b.id)
    }
    fun pickDefault(models: List<ModelInfo>): ModelInfo? = ranked(models).firstOrNull()
    fun selected(models: List<ModelInfo>, choice: String): String = choice.ifBlank { pickDefault(models)?.id ?: OFFLINE_FALLBACK }
    fun forTurn(models: List<ModelInfo>, choice: String, think: Boolean, overrides: Map<String, ModelCapabilities>): String {
        val selected = selected(models, choice)
        fun reasoning(id: String) = (overrides[id] ?: ModelCapabilities()).isReasoning(id)
        return if (think && !reasoning(selected)) ranked(models).firstOrNull { reasoning(it.id) }?.id ?: selected else selected
    }
    fun isReasoning(id: String): Boolean = Regex("reason|think|(?:^|[-_])r[1-9](?:$|[-_.])|^o[134](?:$|[-_])", RegexOption.IGNORE_CASE).containsMatchIn(id)
    fun isVision(id: String): Boolean = Regex("vision|(?:^|[-_])vl(?:$|[-_])|gpt-4o|gpt-4\\.1|gemini|pixtral|llava", RegexOption.IGNORE_CASE).containsMatchIn(id)
}
