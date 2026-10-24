package io.github.linuskromnow.linusexplorer.ui.browser

import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.properties.ChecksumAlgorithm
import io.github.linuskromnow.linusexplorer.fs.properties.FolderStats

/** Permissions of a file on device storage, as seen by this app. */
data class LocalAccess(val readable: Boolean, val writable: Boolean, val executable: Boolean)

/** Live content of the properties dialog; the slow parts fill in while it is open. */
data class PropertiesState(
    val entries: List<FileEntry>,
    val fsId: String,
    /** Folder contents; stays null when every entry is a plain file. */
    val stats: FolderStats? = null,
    val measuring: Boolean = false,
    val access: LocalAccess? = null,
    val checksums: Map<ChecksumAlgorithm, String> = emptyMap(),
    val hashing: Boolean = false,
    /** 0..1 while hashing a file of known size. */
    val hashProgress: Float = 0f,
    val error: String? = null,
) {
    val single: FileEntry? get() = entries.singleOrNull()
    val hasFolders: Boolean get() = entries.any { it.isDirectory }

    /** Total size: the measured one when folders are involved, otherwise just the sum of the files. */
    val totalBytes: Long get() = stats?.bytes ?: entries.sumOf { it.size }
}
