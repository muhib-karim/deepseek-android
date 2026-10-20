package app.dsandroid.core.deepseek

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryGroupsTest {
    private val zone = ZoneId.of("Asia/Dhaka")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val now = at("2026-10-20T09:00:00")

    @Test fun labelsFollowTheOfficialGroups() {
        assertEquals("Today", HistoryGroups.label(at("2026-10-20T00:01:00"), false, now, zone))
        assertEquals("Yesterday", HistoryGroups.label(at("2026-10-19T23:59:00"), false, now, zone))
        assertEquals("7 Days", HistoryGroups.label(at("2026-10-14T12:00:00"), false, now, zone))
        assertEquals("30 Days", HistoryGroups.label(at("2026-09-21T12:00:00"), false, now, zone))
        assertEquals("Sep 2026", HistoryGroups.label(at("2026-09-20T12:00:00"), false, now, zone))
        assertEquals("Dec 2025", HistoryGroups.label(at("2025-12-31T12:00:00"), false, now, zone))
        assertEquals("Pinned", HistoryGroups.label(at("2025-01-01T12:00:00"), true, now, zone))
        assertEquals("Earlier", HistoryGroups.label(0, false, now, zone))
        // A clock that moved backwards still files the chat under Today rather than a future group.
        assertEquals("Today", HistoryGroups.label(at("2026-10-21T08:00:00"), false, now, zone))
    }

    @Test fun groupsArePinnedFirstThenNewest() {
        data class Chat(val id: String, val updated: Long, val pinned: Boolean = false)
        val chats = listOf(Chat("old", at("2026-08-01T10:00:00")), Chat("today", at("2026-10-20T08:00:00")),
            Chat("pin", at("2026-01-01T10:00:00"), pinned = true), Chat("yesterday", at("2026-10-19T08:00:00")), Chat("legacy", 0))
        val groups = HistoryGroups.group(chats, { it.updated }, { it.pinned }, now, zone)
        assertEquals(listOf("Pinned", "Today", "Yesterday", "Aug 2026", "Earlier"), groups.map { it.first })
        assertEquals(listOf("pin", "today", "yesterday", "old", "legacy"), groups.flatMap { g -> g.second.map { it.id } })
    }
}
