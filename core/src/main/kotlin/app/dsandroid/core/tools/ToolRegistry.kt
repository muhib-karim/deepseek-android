package app.dsandroid.core.tools

import app.dsandroid.core.llm.FunctionSpec
import app.dsandroid.core.llm.ToolSpec

/**
 * The tools a run may use. [disabled] tools are left out of the model's schema and are unknown to the guard, so a request
 * for one is denied. [enabled] is checked on every lookup, so a tool switched off during a run (for example while an
 * approval was pending) is neither advertised nor executed afterwards.
 */
class ToolRegistry(tools: List<Tool>, disabled: Set<String> = emptySet(), private val enabled: (String) -> Boolean = { true }) {
    private val entries = tools.filterNot { it.name in disabled }.associateBy { it.name }
    init { require(tools.map { it.name }.distinct().size == tools.size) { "Duplicate tool name" } }
    operator fun get(name: String): Tool? = entries[name]?.takeIf { enabled(name) }
    fun specs(): List<ToolSpec> = entries.values.filter { enabled(it.name) }.map { ToolSpec(function = FunctionSpec(it.name, it.description, it.schema)) }
}
