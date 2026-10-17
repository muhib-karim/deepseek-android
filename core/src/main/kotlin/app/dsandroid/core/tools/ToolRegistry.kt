package app.dsandroid.core.tools

import app.dsandroid.core.llm.FunctionSpec
import app.dsandroid.core.llm.ToolSpec

class ToolRegistry(tools: List<Tool>, disabled: Set<String> = emptySet()) {
    private val entries = tools.filterNot { it.name in disabled }.associateBy { it.name }
    init { require(tools.map { it.name }.distinct().size == tools.size) { "Duplicate tool name" } }
    operator fun get(name: String): Tool? = entries[name]
    fun specs(): List<ToolSpec> = entries.values.map { ToolSpec(function = FunctionSpec(it.name, it.description, it.schema)) }
}
