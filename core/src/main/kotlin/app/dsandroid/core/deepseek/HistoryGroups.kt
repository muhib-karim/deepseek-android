package app.dsandroid.core.deepseek

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The drawer's history groups, as the official app labels them: Pinned, Today, Yesterday, 7 Days, 30 Days, then months. */
object HistoryGroups {
    const val PINNED = "Pinned"
    const val EARLIER = "Earlier"
    private val MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

    /** [updatedAt] is epoch milliseconds; 0 (history saved before v1.0.0) has no date and goes to [EARLIER]. */
    fun label(updatedAt: Long, pinned: Boolean, now: Long, zone: ZoneId): String {
        if (pinned) return PINNED
        if (updatedAt <= 0) return EARLIER
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(day, today)
        return when {
            days <= 0 -> "Today"
            days == 1L -> "Yesterday"
            days < 7 -> "7 Days"
            days < 30 -> "30 Days"
            else -> MONTH.format(day.withDayOfMonth(1))
        }
    }

    /** Groups in display order (pinned first, then newest), keeping each group's items in the given order. */
    fun <T> group(items: List<T>, updatedAt: (T) -> Long, pinned: (T) -> Boolean, now: Long, zone: ZoneId): List<Pair<String, List<T>>> {
        val sorted = items.sortedWith(compareByDescending<T> { pinned(it) }.thenByDescending { updatedAt(it) })
        return sorted.groupBy { label(updatedAt(it), pinned(it), now, zone) }.toList()
    }
}
