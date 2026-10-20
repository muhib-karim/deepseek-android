package app.dsandroid.tools

import org.junit.Assert.*
import org.junit.Test

class ToolCatalogTest {
    @Test fun everyNameBelongsToExactlyOneSwitch() {
        val names = ToolCatalog.entries.flatMap { it.names }
        assertEquals(names.size, names.toSet().size)
        names.forEach { assertTrue(it, it in ToolCatalog.entry(it)!!.names) }
    }

    @Test fun anAliasFollowsItsSwitchBothWays() {
        val off = ToolCatalog.switched(emptySet(), "create_xlsx", on = false)
        assertEquals(setOf("create_xlsx"), off)
        assertFalse(ToolCatalog.isEnabled("create_spreadsheet", off))
        assertEquals(setOf("create_xlsx", "create_spreadsheet"), ToolCatalog.disabledNames(off))
        // A stored alias (an older setting) switches the whole entry off, and switching on clears it too.
        val legacy = setOf("visit_web")
        assertFalse(ToolCatalog.isEnabled("web_fetch", legacy))
        assertEquals(setOf("web_fetch", "visit_web"), ToolCatalog.disabledNames(legacy))
        assertTrue(ToolCatalog.switched(legacy, "web_fetch", on = true).isEmpty())
    }

    @Test fun toolsOutsideTheCatalogAreSwitchedByTheirOwnName() {
        val remote = "mcp_0123456789abcdef0123_00112233445566778899"
        val off = ToolCatalog.switched(setOf("write"), remote, on = false)
        assertEquals(setOf("write", remote), off)
        assertFalse(ToolCatalog.isEnabled(remote, off))
        assertTrue(ToolCatalog.isEnabled("some_skill_function", off))
        assertEquals(setOf("write"), ToolCatalog.switched(off, remote, on = true))
    }

    @Test fun theAccountRouteOffersOnlyToolsItCanRun() {
        val account = ToolCatalog.forRoute(ToolRoute.ACCOUNT).flatMap { it.names }.toSet()
        assertTrue(account.containsAll(listOf("write", "read", "present", "write_file", "create_pdf", "create_xlsx", "create_pptx", "create_chart",
            "create_image", "zip_files", "run_python", "pip_install", "skill", "web_search", "web_fetch")))
        // Phone actions, the in-app browser, memory and scheduling need the API-key client's screens.
        listOf("draft_sms", "open_url", "remember", "schedule_prompt", "parallel_agents").forEach { assertFalse(it, it in account) }
        assertTrue(ToolCatalog.forRoute(ToolRoute.API).flatMap { it.names }.containsAll(account))
    }

    @Test fun networkAndDeviceActionsSayWhenTheyAskFirst() {
        listOf("web_search", "web_fetch", "pip_install", "draft_sms", "remember").forEach { assertNotNull(it, ToolCatalog.entry(it)!!.approval) }
        listOf("write", "create_pdf", "zip_files").forEach { assertNull(it, ToolCatalog.entry(it)!!.approval) }
    }
}
