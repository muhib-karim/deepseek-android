package app.dsandroid.core.tools

import app.dsandroid.core.guard.*
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OfficeAliasesTest {
    @Test fun publicNamesCreateReadablePackagesWithoutOverwriteOrOutsideWrites() = runTest {
        val root = Files.createTempDirectory("office-tools").toFile()
        try {
            val workspace = WorkspacePath(root)
            val tools = listOf(CreateDocumentTool(), CreateSpreadsheetTool(), CreatePresentationTool())
            val registry = ToolRegistry(tools)
            val guard = GuardPolicy(registry, workspace)
            val inputs = listOf(
                """{"fileName":"report.docx","title":"বাংলা & <text>","blocks":[{"paragraph":"Hello"}]}""",
                """{"fileName":"table.xlsx","sheets":[{"name":"Data","rows":[["=SUM(A1)",12]]}]}""",
                """{"fileName":"deck.pptx","title":"Deck","slides":[{"title":"Slide","bullets":["One"]}]}""",
            )
            tools.zip(inputs).forEach { (tool, input) ->
                val args = Json.parseToJsonElement(input).jsonObject
                assertEquals(Decision.ALLOW, guard.decide(tool.name, args, GuardSettings()))
                val result = tool.run(args, ToolContext(workspace))
                assertEquals(1, result.artifacts.size)
                ZipFile(workspace.resolve(result.artifacts.single().path)).use { zip ->
                    assertNotNull(zip.getEntry("[Content_Types].xml"))
                    assertNotNull(zip.getEntry("_rels/.rels"))
                }
                try { tool.run(args, ToolContext(workspace)); fail("Overwrite accepted") } catch (_: java.nio.file.FileAlreadyExistsException) { }
                assertEquals(Decision.DENY, guard.decide(tool.name, JsonObject(args + ("fileName" to JsonPrimitive("../escape.docx"))), GuardSettings()))
            }
        } finally { root.deleteRecursively() }
    }
}
