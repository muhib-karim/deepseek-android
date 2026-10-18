package app.dsandroid.core.python

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.coroutineContext

@Serializable
data class InstalledPackage(val name: String, val version: String, val files: List<String> = emptyList(), val bytes: Long = 0)

data class InstallReport(val installed: List<InstalledPackage>, val alreadyAvailable: List<String>) {
    fun summary(): String = buildString {
        append(if (installed.isEmpty()) "Nothing new was installed." else "Installed ${installed.joinToString { "${it.name} ${it.version}" }}.")
        if (alreadyAvailable.isNotEmpty()) append(" Already available: ${alreadyAvailable.joinToString()}.")
        if (installed.isNotEmpty()) append(" Import them in run_python.")
    }
}

/** Runtime package manager for the private site folder ("Python toolsets" in Settings). */
interface PackageInstaller {
    suspend fun install(requirements: List<String>): InstallReport
    fun installed(): List<InstalledPackage>
    fun remove(name: String): Boolean
}

/**
 * Installs pure-Python wheels (abi "none", platform "any") from PyPI's JSON API into [siteDir]. Each
 * download is checked against PyPI's SHA-256 digest; archive paths are validated before extraction and
 * native code is refused. Dependencies come from the wheel's Requires-Dist with markers evaluated for
 * Android CPython. Chaquopy's runtime has no pip, so this replaces `pip install` on the device.
 */
class WheelInstaller(
    private val siteDir: File,
    private val bundled: Map<String, String> = emptyMap(),
    private val indexUrl: String = "https://pypi.org",
    client: OkHttpClient? = null,
    private val pythonVersion: String = "3.13.0",
    private val machine: String = "aarch64",
) : PackageInstaller {
    companion object {
        const val MAX_WHEEL_BYTES = 25L * 1024 * 1024
        const val MAX_PACKAGES_PER_REQUEST = 25
        private const val MAX_EXPANDED_BYTES = 80L * 1024 * 1024
        private const val INDEX_FILE = ".ds-packages.json"
        private val NATIVE = Regex("(?i).*\\.(so|pyd|dll|dylib)(\\.[0-9.]+)?$")
        private val WHEEL = Regex("(?i)^([A-Za-z0-9_.]+)-([A-Za-z0-9_.!+]+)(?:-[0-9][A-Za-z0-9_.]*)?-([A-Za-z0-9_.]+)-([A-Za-z0-9_.]+)-([A-Za-z0-9_.]+)\\.whl$")
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }

    private val index: HttpUrl = indexUrl.trimEnd('/').toHttpUrl()
    private val lock = Mutex()
    private val http = (client ?: OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
            if (addresses.isEmpty() || addresses.any { !app.dsandroid.core.tools.WebFetchUrlPolicy.isPublicAddress(it) }) throw IOException("Private network destination blocked")
        }
    }).build()).newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

    private val environment: Map<String, String> get() = mapOf(
        "python_version" to pythonVersion.split('.').take(2).joinToString("."),
        "python_full_version" to pythonVersion,
        "sys_platform" to "android", "platform_system" to "Android", "os_name" to "posix",
        "implementation_name" to "cpython", "platform_python_implementation" to "CPython",
        "platform_machine" to machine, "platform_release" to "", "platform_version" to "",
        "implementation_version" to pythonVersion, "extra" to "",
    )

    override fun installed(): List<InstalledPackage> = readIndex().values.sortedBy { it.name.lowercase() }

    override fun remove(name: String): Boolean {
        val key = Pep440.normalize(name)
        val records = readIndex().toMutableMap()
        val record = records.remove(key) ?: return false
        record.files.forEach { path -> resolveInside(path)?.takeIf { it.isFile }?.delete() }
        // Remove directories this package left empty, deepest first.
        record.files.mapNotNull { resolveInside(it)?.parentFile }.distinct().sortedByDescending { it.path.length }.forEach { directory ->
            var current: File? = directory
            while (current != null && current != siteDir.canonicalFile && current.isDirectory && current.list().isNullOrEmpty()) {
                current.delete()
                current = current.parentFile
            }
        }
        writeIndex(records)
        return true
    }

    override suspend fun install(requirements: List<String>): InstallReport = lock.withLock {
        val wanted = ArrayDeque(requirements.map { Pep440.requirement(it).also { requirement -> require(requirement.marker == null) { "Markers are not supported here" } } })
        val installed = mutableListOf<InstalledPackage>()
        val available = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        while (wanted.isNotEmpty()) {
            coroutineContext.ensureActive()
            val requirement = wanted.removeFirst()
            if (!seen.add(requirement.name)) continue
            val bundledVersion = bundled[requirement.name]
            if (bundledVersion != null) { available += "${requirement.name} $bundledVersion (built in)"; continue }
            val existing = readIndex()[requirement.name]
            if (existing != null && Pep440.satisfies(existing.version, requirement.specifiers)) { available += "${existing.name} ${existing.version}"; continue }
            require(installed.size < MAX_PACKAGES_PER_REQUEST) { "Too many dependencies in one install" }
            val wheel = selectWheel(requirement)
            val bytes = download(wheel.url, wheel.size)
            require(sha256(bytes).equals(wheel.sha256, ignoreCase = true)) { "Downloaded ${wheel.filename} does not match its PyPI checksum" }
            val (record, dependencies) = withContext(Dispatchers.IO) { extract(requirement.name, wheel.version, bytes) }
            installed += record
            dependencies.forEach { wanted.addLast(it) }
        }
        InstallReport(installed, available)
    }

    private data class Wheel(val filename: String, val url: String, val sha256: String, val size: Long, val version: String)

    private suspend fun selectWheel(requirement: Pep440.Requirement): Wheel {
        val url = index.newBuilder().addPathSegment("pypi").addPathSegment(requirement.name).addPathSegment("json").build()
        val root = json.parseToJsonElement(get(url, 8L * 1024 * 1024).toString(Charsets.UTF_8)).jsonObject
        val releases = root["releases"] as? JsonObject ?: throw IllegalArgumentException("PyPI has no releases for ${requirement.name}")
        val pinned = requirement.specifiers.any { (operator, version) -> operator in setOf("==", "===") && Pep440.version(version)?.isPrerelease == true }
        val candidates = releases.keys.mapNotNull { version -> Pep440.version(version)?.let { version to it } }
            .filter { (raw, parsed) -> (pinned || !parsed.isPrerelease) && Pep440.satisfies(raw, requirement.specifiers) }
            .sortedByDescending { it.second }
        for ((version, _) in candidates) {
            val files = releases[version] as? JsonArray ?: continue
            for (file in files) {
                val entry = file as? JsonObject ?: continue
                val filename = entry.text("filename") ?: continue
                if (entry.text("packagetype") != "bdist_wheel" || (entry["yanked"] as? JsonPrimitive)?.booleanOrNull == true) continue
                if (!isPureWheel(filename) || !Pep440.pythonCompatible(entry.text("requires_python"), pythonVersion)) continue
                val sha = (entry["digests"] as? JsonObject)?.text("sha256") ?: continue
                val link = entry.text("url") ?: continue
                val size = (entry["size"] as? JsonPrimitive)?.longOrNull ?: continue
                if (size > MAX_WHEEL_BYTES) continue
                return Wheel(filename, link, sha, size, version)
            }
        }
        throw IllegalArgumentException("No pure-Python wheel of ${requirement.name} fits Python ${environment.getValue("python_version")}; packages with native code cannot be installed")
    }

    fun isPureWheel(filename: String): Boolean {
        val match = WHEEL.matchEntire(filename) ?: return false
        val (python, abi, platform) = Triple(match.groupValues[3].lowercase(), match.groupValues[4].lowercase(), match.groupValues[5].lowercase())
        return abi == "none" && platform == "any" && python.split('.').any { it == "py3" || it.startsWith("py3") }
    }

    /** Extracts into a staging folder, then moves files into the site folder and records them for removal. */
    private fun extract(name: String, version: String, bytes: ByteArray): Pair<InstalledPackage, List<Pep440.Requirement>> {
        val site = siteDir.canonicalFile.also { check(it.isDirectory || it.mkdirs()) { "Cannot create the Python site folder" } }
        val staging = File(site, ".staging-${System.nanoTime()}")
        try {
            check(staging.mkdirs())
            val written = mutableListOf<String>()
            var metadata = ""
            var expanded = 0L
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val target = installPath(entry.name) ?: continue
                    require(!NATIVE.matches(target)) { "$name contains native code, which cannot be installed on the device" }
                    val out = File(staging, target).canonicalFile
                    require(out.toPath().startsWith(staging.canonicalFile.toPath())) { "Unsafe path in wheel" }
                    check(out.parentFile.isDirectory || out.parentFile.mkdirs())
                    out.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            expanded += count
                            require(expanded <= MAX_EXPANDED_BYTES) { "$name expands beyond 80 MB" }
                            output.write(buffer, 0, count)
                        }
                    }
                    if (target.matches(Regex("[^/]+\\.dist-info/METADATA"))) metadata = out.readText()
                    written += target
                }
            }
            require(written.isNotEmpty()) { "$name wheel is empty" }
            written.forEach { path ->
                val destination = File(site, path)
                check(destination.parentFile.isDirectory || destination.parentFile.mkdirs())
                Files.move(File(staging, path).toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val record = InstalledPackage(name, version, written.sorted(), written.sumOf { File(site, it).length() })
            val records = readIndex().toMutableMap()
            records[name] = record
            writeIndex(records)
            val dependencies = metadata.lines().filter { it.startsWith("Requires-Dist:", ignoreCase = true) }.mapNotNull { line ->
                runCatching { Pep440.requirement(line.substringAfter(':').trim()) }.getOrNull()
            }.filter { dependency -> dependency.marker == null || Pep440.marker(dependency.marker, environment) }
            return record to dependencies
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Wheel entry path inside the site folder, or null for parts that are not installed (scripts, headers, data). */
    private fun installPath(name: String): String? {
        require(name.isNotEmpty() && !name.startsWith("/") && '\\' !in name && ':' !in name && name.length <= 512) { "Unsafe path in wheel" }
        val parts = name.split('/')
        require(parts.none { it == ".." || it == "." || it.isEmpty() }) { "Unsafe path in wheel" }
        if (parts.first().endsWith(".data")) {
            if (parts.size < 3 || parts[1] !in setOf("purelib", "platlib")) return null
            return parts.drop(2).joinToString("/")
        }
        return name
    }

    private fun resolveInside(path: String): File? {
        val file = File(siteDir, path).canonicalFile
        return file.takeIf { it.toPath().startsWith(siteDir.canonicalFile.toPath()) && it != siteDir.canonicalFile }
    }

    private fun readIndex(): Map<String, InstalledPackage> {
        val file = File(siteDir, INDEX_FILE)
        if (!file.isFile) return emptyMap()
        return runCatching { json.decodeFromString(MapSerializer(String.serializer(), InstalledPackage.serializer()), file.readText()) }.getOrDefault(emptyMap())
    }

    private fun writeIndex(records: Map<String, InstalledPackage>) {
        check(siteDir.isDirectory || siteDir.mkdirs())
        val temporary = File(siteDir, "$INDEX_FILE.tmp")
        temporary.writeText(json.encodeToString(MapSerializer(String.serializer(), InstalledPackage.serializer()), records))
        Files.move(temporary.toPath(), File(siteDir, INDEX_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Downloads come from PyPI's file host (or the configured index host in tests) over the index's scheme. */
    private suspend fun download(link: String, expected: Long): ByteArray {
        val url = link.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid wheel URL")
        require(url.scheme == index.scheme && (url.host == index.host || url.host == "files.pythonhosted.org")) { "Unexpected wheel host ${url.host}" }
        return get(url, minOf(expected, MAX_WHEEL_BYTES)).also { require(it.size.toLong() == expected) { "Incomplete wheel download" } }
    }

    private suspend fun get(url: HttpUrl, maximum: Long): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).header("Accept", "application/json, application/octet-stream")
            .header("User-Agent", "DeepSeek-Android-Unofficial (Python toolsets)").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (it.code == 404) throw IllegalArgumentException("Package not found on PyPI")
                        if (!it.isSuccessful) throw IOException("PyPI returned HTTP ${it.code}")
                        val body = it.body ?: throw IOException("Empty response")
                        if (body.contentLength() > maximum) throw IOException("Download is too large")
                        val output = java.io.ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (!continuation.isActive) throw IOException("Download cancelled")
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > maximum) throw IOException("Download is too large")
                                output.write(buffer, 0, count)
                            }
                        }
                        output.toByteArray()
                    }
                }
                result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        })
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
