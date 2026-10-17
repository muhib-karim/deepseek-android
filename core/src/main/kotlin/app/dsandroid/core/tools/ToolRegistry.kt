package app.dsandroid.core.tools

import app.dsandroid.core.llm.FunctionSpec
import app.dsandroid.core.llm.ToolSpec

class ToolRegistry(tools: List<Tool>) {
    private val entries = tools.associateBy { it.name }
    init { require(entries.size == tools.size) { "Duplicate tool name" } }
    operator fun get(name: String): Tool? = entries[name]
    fun specs(): List<ToolSpec> = entries.values.map { ToolSpec(function = FunctionSpec(it.name, it.description, it.schema)) }
}
