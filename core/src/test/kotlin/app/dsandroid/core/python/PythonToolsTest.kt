package app.dsandroid.core.python

import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.tools.ToolContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PythonToolsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun runPythonReportsOutputAndTurnsNewFilesIntoCards() = runBlocking<Unit> {
        val ctx = ToolContext(WorkspacePath(temporary.newFolder("workspace")), chatDir = "account/chat-9")
        var seen: PythonRun? = null
        val executor = PythonExecutor { run ->
            seen = run
            run.workingDir.resolve("chart.png").writeBytes(byteArrayOf(1, 2, 3))
            run.workingDir.resolve(".hidden").writeText("ignored")
            PythonOutcome(stdout = "4\n")
        }
        val result = RunPythonTool(executor).run(buildJsonObject { put("code", "print(2 + 2)"); put("timeout_s", 5) }, ctx)
        assertFalse(result.isError)
        assertEquals("print(2 + 2)", seen!!.code)
        assertEquals(5, seen!!.timeoutSeconds)
        assertEquals("account/chat-9", seen!!.namespace)
        assertTrue(seen!!.workingDir.path.endsWith("account/chat-9"))
        val json = Json.parseToJsonElement(result.content).jsonObject
        assertEquals("4\n", json["stdout"]!!.jsonPrimitive.content)
        assertEquals(listOf("chart.png"), json["files"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("account/chat-9/chart.png", result.artifacts.single().path)
        assertEquals("image/png", result.artifacts.single().mimeType)
    }

    @Test fun unchangedFilesAreNotReportedAndErrorsAreFlagged() = runBlocking<Unit> {
        val ctx = ToolContext(WorkspacePath(temporary.newFolder("workspace")), chatDir = "c")
        ctx.workspace.root.resolve("c").mkdirs()
        ctx.workspace.root.resolve("c/data.csv").writeText("a\n1")
        val failing = PythonExecutor { PythonOutcome(stderr = "Traceback...\nZeroDivisionError: division by zero", error = "ZeroDivisionError: division by zero") }
        val result = RunPythonTool(failing).run(buildJsonObject { put("code", "1/0") }, ctx)
        assertTrue(result.isError)
        assertTrue(result.artifacts.isEmpty())
        assertEquals("ZeroDivisionError: division by zero", Json.parseToJsonElement(result.content).jsonObject["error"]!!.jsonPrimitive.content)
        val slow = PythonExecutor { PythonOutcome(timedOut = true) }
        assertTrue(Json.parseToJsonElement(RunPythonTool(slow).run(buildJsonObject { put("code", "while True: pass") }, ctx).content)
            .jsonObject["timed_out"]!!.jsonPrimitive.boolean)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { RunPythonTool(slow).run(buildJsonObject { put("code", "x"); put("timeout_s", 500) }, ctx) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { RunPythonTool(slow).run(buildJsonObject { put("code", " ") }, ctx) } }
    }

    @Test fun outputIsBoundedAcrossStreams() = runBlocking<Unit> {
        val ctx = ToolContext(WorkspacePath(temporary.newFolder("workspace")))
        val noisy = PythonExecutor { PythonOutcome(stdout = "o".repeat(PythonTools.MAX_OUTPUT_CHARS), stderr = "e".repeat(100)) }
        val json = Json.parseToJsonElement(RunPythonTool(noisy).run(buildJsonObject { put("code", "noise()") }, ctx).content).jsonObject
        assertEquals(PythonTools.MAX_OUTPUT_CHARS, json["stdout"]!!.jsonPrimitive.content.length)
        assertEquals("", json["stderr"]!!.jsonPrimitive.content)
        assertTrue(json["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun pep440VersionsSpecifiersAndMarkers() {
        assertTrue(Pep440.version("1.10")!! > Pep440.version("1.9.9")!!)
        assertTrue(Pep440.version("2.0rc1")!! < Pep440.version("2.0")!!)
        assertTrue(Pep440.version("2.0.dev1")!! < Pep440.version("2.0a1")!!)
        assertTrue(Pep440.version("2.0.post1")!! > Pep440.version("2.0")!!)
        assertTrue(Pep440.version("2.0b2")!!.isPrerelease)
        assertTrue(Pep440.satisfies("2.31.0", listOf(">=" to "2.0", "<" to "3")))
        assertFalse(Pep440.satisfies("3.0", listOf(">=" to "2.0", "<" to "3")))
        assertTrue(Pep440.satisfies("1.4.7", listOf("~=" to "1.4.5")))
        assertFalse(Pep440.satisfies("1.5.0", listOf("~=" to "1.4.5")))
        assertTrue(Pep440.satisfies("3.9.2", listOf("==" to "3.9.*")))
        assertFalse(Pep440.satisfies("3.9.2", listOf("!=" to "3.9.*")))
        val requirement = Pep440.requirement("Python_Slugify[unidecode] (>=8.0, <9) ; python_version >= \"3.8\"")
        assertEquals("python-slugify", requirement.name)
        assertEquals(listOf(">=" to "8.0", "<" to "9"), requirement.specifiers)
        assertEquals("python_version >= \"3.8\"", requirement.marker)
        assertThrows(IllegalArgumentException::class.java) { Pep440.requirement("pkg @ https://example.com/pkg.whl") }
        assertTrue(Pep440.pythonCompatible(">=3.7, !=3.9.*", "3.13.0"))
        assertFalse(Pep440.pythonCompatible("<3.8", "3.13.0"))
        val env = mapOf("python_version" to "3.13", "python_full_version" to "3.13.0", "sys_platform" to "android", "extra" to "", "os_name" to "posix")
        assertTrue(Pep440.marker("python_version >= \"3.8\" and os_name == 'posix'", env))
        assertFalse(Pep440.marker("sys_platform == \"win32\" or extra == \"socks\"", env))
        assertTrue(Pep440.marker("(sys_platform != 'win32') and \"3.9\" <= python_version", env))
        assertFalse(Pep440.marker("platform_machine == 'arm64'", env))
        assertFalse(Pep440.marker("python_version >>> '3'", env))
    }

    // --- Wheel installer against a fake PyPI ---

    private fun wheel(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class FakePyPi(private val server: MockWebServer) : Dispatcher() {
        val packages = mutableMapOf<String, String>()
        val files = mutableMapOf<String, ByteArray>()
        fun publish(name: String, version: String, filename: String, bytes: ByteArray, digest: String? = null, requiresPython: String? = null, extraReleases: String = "") {
            files[filename] = bytes
            val url = server.url("/files/$filename").toString()
            val file = buildJsonObject {
                put("filename", filename); put("packagetype", if (filename.endsWith(".whl")) "bdist_wheel" else "sdist")
                put("url", url); put("size", bytes.size); put("yanked", false)
                requiresPython?.let { put("requires_python", it) }
                put("digests", buildJsonObject { put("sha256", digest ?: MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) })
            }
            packages[name] = """{"info":{"name":"$name","version":"$version"},"releases":{"$version":[$file]$extraReleases}}"""
        }
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            if (path.startsWith("/pypi/")) return packages[path.removePrefix("/pypi/").removeSuffix("/json")]
                ?.let { MockResponse().setHeader("Content-Type", "application/json").setBody(it) } ?: MockResponse().setResponseCode(404)
            if (path.startsWith("/files/")) return files[path.removePrefix("/files/")]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
            return MockResponse().setResponseCode(404)
        }
    }

    @Test fun installsPureWheelsWithDependenciesAndRemovesThem() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val pypi = FakePyPi(server)
            server.dispatcher = pypi
            pypi.publish("tabulate", "0.9.0", "tabulate-0.9.0-py3-none-any.whl", wheel(mapOf(
                "tabulate/__init__.py" to "def tabulate(rows): return str(rows)",
                "tabulate-0.9.0.dist-info/METADATA" to "Name: tabulate\nVersion: 0.9.0\nRequires-Dist: wcwidth ; extra == \"widechars\"\nRequires-Dist: helper>=1.0\nRequires-Dist: winonly ; sys_platform == \"win32\"\nRequires-Dist: numpy\n",
                "tabulate-0.9.0.data/scripts/tabulate" to "#!python",
                "tabulate-0.9.0.data/purelib/tabulate_extra.py" to "X = 1",
            )), extraReleases = ""","1.0.0b1":[]""")
            pypi.publish("helper", "1.2.0", "helper-1.2.0-py2.py3-none-any.whl", wheel(mapOf(
                "helper.py" to "VALUE = 2", "helper-1.2.0.dist-info/METADATA" to "Name: helper\nVersion: 1.2.0\n")))
            val site = temporary.newFolder("site")
            val installer = WheelInstaller(site, bundled = mapOf("numpy" to "1.26.2"), indexUrl = server.url("/").toString(), client = OkHttpClient())
            val report = installer.install(listOf("tabulate"))
            assertEquals(listOf("tabulate", "helper"), report.installed.map { it.name })
            assertTrue(report.alreadyAvailable.single().startsWith("numpy 1.26.2"))
            assertTrue(site.resolve("tabulate/__init__.py").isFile)
            assertTrue(site.resolve("tabulate_extra.py").isFile)
            assertFalse(site.resolve("tabulate-0.9.0.data").exists())
            assertTrue(site.resolve("helper.py").isFile)
            assertEquals(listOf("helper", "tabulate"), installer.installed().map { it.name })
            assertTrue(report.summary().contains("Installed tabulate 0.9.0, helper 1.2.0."))
            val again = installer.install(listOf("tabulate>=0.9"))
            assertTrue(again.installed.isEmpty())
            assertTrue(installer.remove("Tabulate"))
            assertFalse(site.resolve("tabulate").exists())
            assertFalse(site.resolve("tabulate_extra.py").exists())
            assertTrue(site.resolve("helper.py").isFile)
            assertEquals(listOf("helper"), installer.installed().map { it.name })
        }
    }

    @Test fun refusesChecksumMismatchesNativeCodeUnsafePathsAndMissingWheels() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val pypi = FakePyPi(server)
            server.dispatcher = pypi
            val site = temporary.newFolder("site")
            val installer = WheelInstaller(site, indexUrl = server.url("/").toString(), client = OkHttpClient())
            pypi.publish("tampered", "1.0", "tampered-1.0-py3-none-any.whl", wheel(mapOf("t.py" to "")), digest = "0".repeat(64))
            assertTrue(runCatching { installer.install(listOf("tampered")) }.exceptionOrNull()!!.message!!.contains("checksum"))
            pypi.publish("native", "1.0", "native-1.0-py3-none-any.whl", wheel(mapOf("native/_speedups.cpython-313-aarch64-linux-android.so" to "ELF")))
            assertTrue(runCatching { installer.install(listOf("native")) }.exceptionOrNull()!!.message!!.contains("native code"))
            pypi.publish("escape", "1.0", "escape-1.0-py3-none-any.whl", wheel(mapOf("../../evil.py" to "boom")))
            assertTrue(runCatching { installer.install(listOf("escape")) }.exceptionOrNull()!!.message!!.contains("Unsafe path"))
            pypi.publish("compiled", "1.0", "compiled-1.0-cp313-cp313-manylinux_2_17_x86_64.whl", wheel(mapOf("c.py" to "")))
            assertTrue(runCatching { installer.install(listOf("compiled")) }.exceptionOrNull()!!.message!!.contains("No pure-Python wheel"))
            pypi.publish("oldpy", "1.0", "oldpy-1.0-py3-none-any.whl", wheel(mapOf("o.py" to "")), requiresPython = "<3.8")
            assertTrue(runCatching { installer.install(listOf("oldpy")) }.exceptionOrNull()!!.message!!.contains("No pure-Python wheel"))
            assertTrue(runCatching { installer.install(listOf("missing")) }.exceptionOrNull()!!.message!!.contains("not found"))
            assertFalse(site.parentFile.resolve("evil.py").exists())
            assertTrue(installer.installed().isEmpty())
            assertTrue(installer.isPureWheel("six-1.16.0-py2.py3-none-any.whl"))
            assertFalse(installer.isPureWheel("numpy-1.26.2-cp313-cp313-android_24_arm64_v8a.whl"))
            assertFalse(installer.isPureWheel("pkg-1.0.tar.gz"))
        }
    }
}
