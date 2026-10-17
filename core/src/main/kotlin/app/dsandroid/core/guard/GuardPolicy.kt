package app.dsandroid.core.guard

import app.dsandroid.core.tools.Risk
import app.dsandroid.core.tools.ToolKind
import app.dsandroid.core.tools.ToolRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class Decision { ALLOW, CONFIRM, DENY }
data class GuardSettings(val shellEnabled: Boolean = false, val installEnabled: Boolean = false)

class GuardPolicy(private val registry: ToolRegistry, private val workspace: WorkspacePath) {
    fun decide(name: String, args: JsonObject, settings: GuardSettings): Decision {
        val tool = registry[name] ?: return Decision.DENY
        when (tool.kind) {
            ToolKind.SHELL -> return if (settings.shellEnabled) Decision.CONFIRM else Decision.DENY
            ToolKind.INSTALL -> return if (settings.installEnabled) Decision.CONFIRM else Decision.DENY
            else -> Unit
        }
        if (tool.kind == ToolKind.WRITE || tool.kind == ToolKind.READ) {
            val fields = listOfNotNull(args["path"], args["fileName"], args["zip_path"]) + ((args["sources"] as? kotlinx.serialization.json.JsonArray)?.toList() ?: emptyList())
            if (args.containsKey("sources") && args["sources"] !is kotlinx.serialization.json.JsonArray) return Decision.DENY
            if (fields.isEmpty() && tool.kind == ToolKind.WRITE && name !in setOf("create_docx","create_pptx","create_xlsx","create_chart","create_image")) return Decision.DENY
            var outside = false
            for (field in fields) {
                if (field !is JsonPrimitive || !field.isString) return Decision.DENY
                val target = try { workspace.resolve(field.content, allowOutside = true) }
                catch (_: Exception) { return Decision.DENY }
                if (!workspace.contains(target)) outside = true
            }
            if (outside) return if (tool.kind == ToolKind.WRITE) Decision.CONFIRM else Decision.DENY
        }
        return if (tool.risk == Risk.SAFE) Decision.ALLOW else Decision.CONFIRM
    }
}
