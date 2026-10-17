package app.dsandroid.core.guard

import app.dsandroid.core.tools.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtendedGuardTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun generatedDefaultNamesAreAllowedButZipSourcesAreValidated() {
        val guard=GuardPolicy(ToolRegistry(listOf(CreateDocxTool(),ZipFilesTool(),ListZipTool())),WorkspacePath(temporary.newFolder()))
        assertEquals(Decision.ALLOW,guard.decide("create_docx",buildJsonObject { put("title","Title"); put("blocks",JsonArray(emptyList())) },GuardSettings()))
        assertEquals(Decision.DENY,guard.decide("zip_files",buildJsonObject { put("sources",JsonArray(listOf(JsonPrimitive("../escape")))); put("zip_path","out.zip") },GuardSettings()))
        assertEquals(Decision.DENY,guard.decide("list_zip",buildJsonObject { put("zip_path","../escape.zip") },GuardSettings()))
    }
}
