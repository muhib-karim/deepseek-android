package app.dsandroid.core.artifacts

/** Offline, original renderer for basic Mermaid flowcharts and sequence diagrams.
 * Unsupported directives fail explicitly; source is never executed or partially interpreted.
 */
object MermaidPreview {
    private data class Edge(val from: String, val to: String, val label: String)
    private val identifier = "[A-Za-z_][A-Za-z0-9_]*"
    private val node = "($identifier)(?:\\[([^\\]\\r\\n]*)\\]|\\(([^)\\r\\n]*)\\)|\\{([^}\\r\\n]*)\\})?"
    private val nodePattern = Regex(node)
    private val edgePattern = Regex("$node\\s*(?:-->|---|==>|-\\.->)\\s*(?:\\|([^|]*)\\|\\s*)?$node")

    fun render(source: String): String? = runCatching {
        require(source.length <= 20_000)
        val lines = source.lines().map { it.trim().removeSuffix(";").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("%%") }
        require(lines.size in 2..121)
        when {
            Regex("(?:flowchart|graph) (?:TD|TB|BT|LR|RL)").matches(lines.first()) -> flow(lines)
            lines.first() == "sequenceDiagram" -> sequence(lines.drop(1))
            else -> null
        }
    }.getOrNull()

    private fun flow(lines: List<String>): String {
        val nodes = linkedMapOf<String, String>()
        val edges = mutableListOf<Edge>()
        fun add(groups: List<String>, offset: Int): String {
            val id = groups[offset]
            val label = groups.subList(offset + 1, offset + 4).firstOrNull { it.isNotEmpty() }?.removeSurrounding("\"")
            require((label?.length ?: 0) <= 100)
            if (label != null || id !in nodes) nodes[id] = label ?: id
            return id
        }
        lines.drop(1).forEach { line ->
            val edge = edgePattern.matchEntire(line)
            if (edge != null) {
                val from = add(edge.groupValues, 1)
                val to = add(edge.groupValues, 6)
                require(edge.groupValues[5].length <= 100)
                edges.add(Edge(from, to, edge.groupValues[5]))
            } else {
                val match = requireNotNull(nodePattern.matchEntire(line))
                require(match.groupValues[1] !in setOf("end", "subgraph", "classDef", "style", "click"))
                add(match.groupValues, 1)
            }
        }
        require(nodes.size in 1..40 && edges.size <= 80)
        val horizontal = lines.first().endsWith("LR") || lines.first().endsWith("RL")
        val reverse = lines.first().endsWith("BT") || lines.first().endsWith("RL")
        val ids = if (reverse) nodes.keys.reversed() else nodes.keys.toList()
        val width = if (horizontal) ids.size * 220 + 40 else 420
        val height = if (horizontal) 240 else ids.size * 120 + 40
        fun x(id: String) = if (horizontal) 20 + ids.indexOf(id) * 220 else 110
        fun y(id: String) = if (horizontal) 110 else 20 + ids.indexOf(id) * 120
        return svg(width, height) {
            edges.forEach { edge ->
                val x1 = x(edge.from) + if (horizontal) 170 else 85
                val y1 = y(edge.from) + if (horizontal) 24 else 48
                val x2 = x(edge.to) + if (horizontal) 0 else 85
                val y2 = y(edge.to) + if (horizontal) 24 else 0
                append("<path d=\"M$x1 $y1 L$x2 $y2\" stroke=\"#526178\" fill=\"none\" marker-end=\"url(#arrow)\"/>")
                label((x1 + x2) / 2, (y1 + y2) / 2 - 8, edge.label)
            }
            ids.forEach { id ->
                append("<rect x=\"${x(id)}\" y=\"${y(id)}\" width=\"170\" height=\"48\" rx=\"8\" fill=\"#edf3ff\" stroke=\"#4169a1\"/>")
                label(x(id) + 85, y(id) + 29, nodes.getValue(id))
            }
        }
    }

    private fun sequence(lines: List<String>): String {
        val participants = linkedMapOf<String, String>()
        val messages = mutableListOf<Edge>()
        val declaration = Regex("(?:participant|actor) ($identifier)(?: as (.{1,100}))?")
        val message = Regex("($identifier)\\s*(?:->>|-->>|->|-->)\\s*($identifier)\\s*:\\s*(.{0,160})")
        lines.forEach { line ->
            val declared = declaration.matchEntire(line)
            if (declared != null) participants[declared.groupValues[1]] = declared.groupValues[2].ifEmpty { declared.groupValues[1] }
            else {
                val match = requireNotNull(message.matchEntire(line))
                val from = match.groupValues[1]; val to = match.groupValues[2]
                participants.putIfAbsent(from, from); participants.putIfAbsent(to, to)
                messages.add(Edge(from, to, match.groupValues[3]))
            }
        }
        require(participants.size in 1..12 && messages.size <= 100)
        val width = participants.size * 200 + 40
        val height = messages.size * 65 + 130
        fun x(id: String) = 120 + participants.keys.indexOf(id) * 200
        return svg(width, height) {
            participants.forEach { (id, title) ->
                append("<rect x=\"${x(id) - 85}\" y=\"15\" width=\"170\" height=\"45\" rx=\"5\" fill=\"#edf3ff\" stroke=\"#4169a1\"/>")
                label(x(id), 43, title)
                append("<path d=\"M${x(id)} 60 V${height - 15}\" stroke=\"#a2adbc\" stroke-dasharray=\"4 4\"/>")
            }
            messages.forEachIndexed { index, edge ->
                val y = 105 + index * 65
                val path = if (edge.from == edge.to) "M${x(edge.from)} $y h65 v25 h-65" else "M${x(edge.from)} $y H${x(edge.to)}"
                append("<path d=\"$path\" fill=\"none\" stroke=\"#526178\" marker-end=\"url(#arrow)\"/>")
                label((x(edge.from) + x(edge.to)) / 2, y - 10, edge.label)
            }
        }
    }

    private fun svg(width: Int, height: Int, body: StringBuilder.() -> Unit): String = buildString {
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\"><defs><marker id=\"arrow\" markerWidth=\"8\" markerHeight=\"8\" refX=\"7\" refY=\"4\" orient=\"auto\"><path d=\"M0 0 L8 4 L0 8 Z\" fill=\"#526178\"/></marker></defs>")
        body(); append("</svg>")
    }
    private fun StringBuilder.label(x: Int, y: Int, text: String) {
        append("<text x=\"$x\" y=\"$y\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"14\" fill=\"#172337\">${escapeMarkup(text)}</text>")
    }
}
