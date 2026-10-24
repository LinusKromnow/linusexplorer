package io.github.linuskromnow.linusexplorer.fs

/** One file or directory as reported by a [VirtualFileSystem]. */
data class FileEntry(
    val name: String,
    /** Absolute, "/"-separated path inside the owning file system. */
    val path: String,
    val isDirectory: Boolean,
    /** Size in bytes; 0 for directories. */
    val size: Long,
    /** Epoch millis, or 0 when the backend does not know. */
    val lastModified: Long,
    val isHidden: Boolean = name.startsWith("."),
    val isSymlink: Boolean = false,
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
}

/** Points at a path inside a specific registered file system. */
data class Location(val fsId: String, val path: String)
