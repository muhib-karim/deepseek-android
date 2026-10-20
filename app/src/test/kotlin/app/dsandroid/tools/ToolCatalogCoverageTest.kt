package app.dsandroid.tools

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** e2e/coverage.json (what the release E2E run must show) names every tool the account route offers. */
class ToolCatalogCoverageTest {
    private val cases = Json.parseToJsonElement(File("../e2e/coverage.json").readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test fun everyAccountToolHasARequiredEndToEndCase() {
        val covered = cases.map { it.getValue("tool").jsonPrimitive.content }.toSet()
        val missing = ToolCatalog.forRoute(ToolRoute.ACCOUNT).map { it.name }.filterNot { it in covered }
        assertEquals("Account tools without an E2E case", emptyList<String>(), missing)
    }

    @Test fun trustedToolsAreExpectedRefusedOnAFreshInstall() {
        ToolCatalog.entries.filter { it.trusted }.forEach { tool ->
            assertTrue(tool.name, cases.any { it.getValue("tool").jsonPrimitive.content == tool.name &&
                !it.getValue("ok").jsonPrimitive.boolean && !it.getValue("offered").jsonPrimitive.boolean })
        }
    }
}
