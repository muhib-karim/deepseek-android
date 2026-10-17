package app.dsandroid.core.tools

import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

object SvgSanitizer {
    private val elements = setOf("svg", "g", "defs", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "text", "tspan", "title", "desc", "use", "image", "clipPath", "linearGradient", "radialGradient", "stop")
    private val attributes = setOf("id", "xmlns", "xmlns:xlink", "viewBox", "width", "height", "x", "y", "x1", "x2", "y1", "y2", "cx", "cy", "r", "rx", "ry", "d", "points", "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "opacity", "transform", "font-size", "font-family", "font-weight", "text-anchor", "dx", "dy", "offset", "stop-color", "stop-opacity", "gradientUnits", "gradientTransform", "clip-path", "href", "xlink:href", "preserveAspectRatio")

    fun sanitize(input: String): String {
        require(input.length <= 2 * 1024 * 1024) { "SVG too large" }
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(input)) { "DTD and entities are forbidden" }
        val withoutDeclaration = input.trimStart().replaceFirst(Regex("^<\\?xml\\s[^?]*\\?>"), "")
        require(!withoutDeclaration.contains("<?")) { "Processing instructions are forbidden" }
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw SAXException("External entities are forbidden") }
                setErrorHandler(object : DefaultHandler() {
                    override fun error(e: org.xml.sax.SAXParseException) { throw e }
                    override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
                })
            }
            val document = builder.parse(InputSource(StringReader(input)))
            val root = document.documentElement
            require((root.localName ?: root.tagName) == "svg" && validNamespace(root)) { "Expected an SVG root" }
            clean(root)
            val output = StringWriter()
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            }.transform(DOMSource(root), StreamResult(output))
            return output.toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid or unsafe SVG")
        }
    }

    private fun validNamespace(element: Element) = element.namespaceURI.isNullOrEmpty() || element.namespaceURI == "http://www.w3.org/2000/svg"

    private fun clean(element: Element, depth: Int = 0) {
        require(depth <= 64) { "SVG nesting is too deep" }
        for (index in element.attributes.length - 1 downTo 0) {
            val attribute = element.attributes.item(index)
            val name = attribute.nodeName
            val value = attribute.nodeValue.trim()
            val localReference = value.matches(Regex("#[A-Za-z_][A-Za-z0-9_.-]*"))
            val safeUrl = !value.contains("url", ignoreCase = true) || value.matches(Regex("url\\(#[A-Za-z_][A-Za-z0-9_.-]*\\)"))
            if (name !in attributes || name.startsWith("on", ignoreCase = true) ||
                ((name == "href" || name == "xlink:href") && !localReference) ||
                (!name.startsWith("xmlns") && (!safeUrl || value.contains(':') || value.contains('\\')))) {
                element.removeAttributeNode(attribute as org.w3c.dom.Attr)
            }
        }
        for (index in element.childNodes.length - 1 downTo 0) {
            val child = element.childNodes.item(index)
            if (child is Element) {
                if ((child.localName ?: child.tagName) !in elements || !validNamespace(child)) element.removeChild(child)
                else clean(child, depth + 1)
            } else if (child.nodeType != Node.TEXT_NODE && child.nodeType != Node.CDATA_SECTION_NODE) element.removeChild(child)
        }
    }
}

class CreateSvgTool : Tool {
    override val name = "create_svg"
    override val description = "Create a sanitized static SVG artifact. Scripts, remote resources and styles are removed."
    override val risk = Risk.SAFE
    override val kind = ToolKind.WRITE
    override val schema = objectSchema(mapOf("fileName" to stringSchema("A new file ending in .svg"), "content" to stringSchema("Well-formed SVG XML")))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = writeArtifact(ctx, args.stringArg("fileName"), ".svg", "image/svg+xml",
        SvgSanitizer.sanitize(args.stringArg("content")).toByteArray(Charsets.UTF_8))
}
