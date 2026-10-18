package app.dsandroid.core.widget

data class WidgetChat(val id: String, val title: String, val createdAt: Long, val temporary: Boolean = false)

object WidgetSummary {
    fun select(chats: List<WidgetChat>, currentId: String?): WidgetChat? {
        val saved = chats.filter { !it.temporary && it.title.isNotBlank() }
        val selected = saved.find { it.id == currentId } ?: saved.maxByOrNull { it.createdAt } ?: return null
        return selected.copy(title = selected.title.replace(Regex("[\\p{Cntrl}]"), " ").take(120))
    }
}
