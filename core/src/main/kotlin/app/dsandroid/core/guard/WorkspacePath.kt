package app.dsandroid.core.guard

import java.io.File
import java.nio.file.Files

/** Paths are literal filesystem paths, never URI- or percent-decoded. */
class WorkspacePath(root: File) {
    val root: File = root.canonicalFile

    fun resolve(path: String, allowOutside: Boolean = false): File {
        require(path.isNotBlank() && path.none { it.code < 32 || it == '\\' || it == '%' || it == ':' || it == '~' }) { "Invalid workspace path" }
        require(path.split('/').none { it == ".." }) { "Path traversal is forbidden" }
        val input = File(path)
        val candidate = if (input.isAbsolute) input else File(root, path)
        var ancestor: File? = candidate.absoluteFile
        while (ancestor != null) {
            require(!Files.isSymbolicLink(ancestor.toPath())) { "Symbolic links are forbidden" }
            ancestor = ancestor.parentFile
        }
        val canonical = candidate.canonicalFile
        require(allowOutside || contains(canonical)) { "Path is outside the workspace" }
        return canonical
    }

    fun contains(file: File): Boolean = file.canonicalFile.toPath().startsWith(root.toPath())
    fun relative(file: File): String {
        require(contains(file)) { "Path is outside the workspace" }
        return root.toPath().relativize(file.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')
    }
}
