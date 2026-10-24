package io.github.linuskromnow.linusexplorer.fs.properties

import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import io.github.linuskromnow.linusexplorer.fs.search.TreeWalker
import io.github.linuskromnow.linusexplorer.fs.search.WalkControl
import io.github.linuskromnow.linusexplorer.fs.search.WalkOptions

/** Totals for a set of files and folders. Folders that were selected themselves are not counted in [folders]. */
data class FolderStats(
    val bytes: Long = 0,
    val files: Int = 0,
    val folders: Int = 0,
    /** Folders that could not be read; their content is missing from the totals. */
    val unreadable: Int = 0,
)

object FolderSizer {
    private const val PROGRESS_EVERY = 50

    /**
     * Adds up [entries]: files count directly, folders recursively (hidden files included, symbolic links
     * counted as files and never followed). [onProgress] receives the running totals every few entries.
     * Cancel the calling coroutine to stop.
     */
    suspend fun measure(
        fs: VirtualFileSystem,
        entries: List<FileEntry>,
        onProgress: (FolderStats) -> Unit = {},
    ): FolderStats {
        var bytes = 0L
        var files = 0
        var folders = 0
        var unreadable = 0
        var sinceReport = 0
        fun stats() = FolderStats(bytes, files, folders, unreadable)
        fun count(entry: FileEntry) {
            if (entry.isDirectory && !entry.isSymlink) {
                folders++
            } else {
                files++
                bytes += entry.size
            }
            if (++sinceReport >= PROGRESS_EVERY) {
                sinceReport = 0
                onProgress(stats())
            }
        }
        for (root in entries) {
            if (root.isDirectory && !root.isSymlink) {
                val result = TreeWalker.walk(fs, root.path, WalkOptions(maxDepth = Int.MAX_VALUE)) { walked ->
                    count(walked.entry)
                    WalkControl.CONTINUE
                }
                unreadable += result.skippedDirectories
            } else {
                count(root)
            }
        }
        return stats().also(onProgress)
    }
}
