package app.dsandroid.core.sandbox

import org.junit.Assert.*
import org.junit.Test

class PythonRuntimeTest {
    @Test fun cachedResourcesResolveOnlyExactPrivateRuntimePaths() {
        assertEquals("pyodide.asm.wasm", PythonRuntime.cachedFile(PythonRuntime.CACHE_BASE_URL + "pyodide.asm.wasm"))
        for (url in listOf(PythonRuntime.BASE_URL + "pyodide.js",
            PythonRuntime.CACHE_BASE_URL + "pyodide.js?data=private",
            PythonRuntime.CACHE_BASE_URL + "../secret",
            PythonRuntime.CACHE_BASE_URL + "package.whl",
            PythonRuntime.CACHE_BASE_URL + "pyodide.js#fragment",
            "file:///pyodide.js")) assertNull(PythonRuntime.cachedFile(url))
    }

    @Test fun onlyExactPinnedRuntimeResourcesAreAccepted() {
        assertTrue(PythonRuntime.isAllowedUrl("https://cdn.jsdelivr.net/pyodide/v0.26.4/full/pyodide.js"))
        for (url in listOf(
            "https://cdn.jsdelivr.net/pyodide/v0.26.4/full/pyodide.js?code=private",
            "https://cdn.jsdelivr.net/pyodide/v0.26.4/full/../other.js",
            "https://cdn.jsdelivr.net.evil.test/pyodide/v0.26.4/full/pyodide.js",
            "http://cdn.jsdelivr.net/pyodide/v0.26.4/full/pyodide.js",
            "https://cdn.jsdelivr.net/pyodide/v0.26.4/full/package.whl"
        )) assertFalse(url, PythonRuntime.isAllowedUrl(url))
    }
}
