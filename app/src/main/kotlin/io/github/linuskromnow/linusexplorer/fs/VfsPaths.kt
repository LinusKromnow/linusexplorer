package io.github.linuskromnow.linusexplorer.fs

/** Helpers for the "/"-separated absolute paths used by every [VirtualFileSystem]. */
object VfsPaths {
    const val ROOT = "/"

    /** Collapses duplicate slashes, resolves "." and "..", drops trailing slashes. */
    fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString(separator = "/", prefix = "/")
    }

    /** Parent directory, or null for the root. */
    fun parent(path: String): String? {
        val normalized = normalize(path)
        if (normalized == ROOT) return null
        val index = normalized.lastIndexOf('/')
        return if (index == 0) ROOT else normalized.substring(0, index)
    }

    fun name(path: String): String {
        val normalized = normalize(path)
        return if (normalized == ROOT) ROOT else normalized.substringAfterLast('/')
    }

    fun child(directory: String, name: String): String =
        normalize(if (directory.endsWith('/')) directory + name else "$directory/$name")

    /** True if [path] equals [ancestor] or lies somewhere below it. */
    fun isAncestorOrSelf(ancestor: String, path: String): Boolean {
        val a = normalize(ancestor)
        val p = normalize(path)
        return a == p || a == ROOT || p.startsWith("$a/")
    }

    /** Segments of [path] below [base]; empty if they are equal. Requires [isAncestorOrSelf]. */
    fun relativeSegments(base: String, path: String): List<String> =
        normalize(path).removePrefix(normalize(base)).split('/').filter { it.isNotEmpty() }

    /** Whether [name] can be used as a single file or folder name. */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name
}
