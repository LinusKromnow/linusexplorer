package io.github.linuskromnow.linusexplorer.fs.search

import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem

/** A match, with its path relative to the folder the search started in (e.g. "photos/2024/cat.jpg"). */
data class SearchHit(val entry: FileEntry, val relativePath: String)

data class SearchLimits(
    val maxResults: Int = 1_000,
    val maxDepth: Int = WalkOptions.DEFAULT_MAX_DEPTH,
    val includeHidden: Boolean = false,
)

data class SearchSummary(
    /** Number of entries looked at. */
    val scanned: Int,
    /** The search stopped early because [SearchLimits.maxResults] was reached. */
    val truncated: Boolean,
    val skippedDirectories: Int,
)

/** Recursive search by name; works on any [VirtualFileSystem] (device storage, vault, archives). */
object FileSearch {
    private const val PROGRESS_EVERY = 200

    /**
     * Calls [onHit] for each match as soon as it is found and [onProgress] now and then with the number
     * of entries scanned so far. Cancel the calling coroutine to stop.
     */
    suspend fun search(
        fs: VirtualFileSystem,
        root: String,
        matcher: NameMatcher,
        limits: SearchLimits = SearchLimits(),
        onProgress: (scanned: Int) -> Unit = {},
        onHit: suspend (SearchHit) -> Unit,
    ): SearchSummary {
        var scanned = 0
        var hits = 0
        val result = TreeWalker.walk(fs, root, WalkOptions(limits.includeHidden, limits.maxDepth)) { walked ->
            scanned++
            if (scanned % PROGRESS_EVERY == 0) onProgress(scanned)
            if (matcher.matches(walked.entry.name)) {
                onHit(SearchHit(walked.entry, walked.relativePath))
                if (++hits >= limits.maxResults) return@walk WalkControl.STOP
            }
            WalkControl.CONTINUE
        }
        onProgress(scanned)
        return SearchSummary(scanned, truncated = result.stopped, skippedDirectories = result.skippedDirectories)
    }
}
