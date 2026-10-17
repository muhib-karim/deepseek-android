package app.dsandroid.core.tools

import org.junit.Assert.*
import org.junit.Test

class SvgSanitizerTest {
    @Test fun removesExecutableAndExternalContentKeepsLocalDrawing() {
        val result = SvgSanitizer.sanitize("""<svg xmlns="http://www.w3.org/2000/svg" onload="bad()"><script>bad()</script><style>bad</style><rect id="shape" width="20"/><use href="#shape"/><image href="https://example.org/image"/><foreignObject><p>bad</p></foreignObject><circle style="fill:url(https://example.org/x)" onclick="bad()"/></svg>""")
        assertTrue(result.contains("rect"))
        assertTrue(result.contains("#shape"))
        listOf("script", "onload", "onclick", "example.org", "foreignObject", "style=").forEach { assertFalse(it, result.contains(it)) }
    }
    @Test fun rejectsExcessiveNestingBeforeRecursiveCleanupCanOverflow() {
        val nested = "<svg>" + "<g>".repeat(100) + "<rect/>" + "</g>".repeat(100) + "</svg>"
        assertThrows(IllegalArgumentException::class.java) { SvgSanitizer.sanitize(nested) }
    }
    @Test fun rejectsWrongRootMalformedXmlAndEntities() {
        listOf("<html/>", "<svg><rect></svg>", "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///unused'>]><svg>&x;</svg>", "<?xml-stylesheet href='https://example.org/x'?><svg/>").forEach {
            assertThrows(IllegalArgumentException::class.java) { SvgSanitizer.sanitize(it) }
        }
    }
}
