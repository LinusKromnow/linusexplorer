package io.github.linuskromnow.linusexplorer.fs.search

import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One entry found below the walk root. [depth] is 1 for direct children. */
data class WalkEntry(val entry: FileEntry, val relativePath: String, val depth: Int)

data class WalkOptions(
    /** Hidden entries are neither reported nor entered when false. */
    val includeHidden: Boolean = true,
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    companion object {
        /** Deep enough for any real tree, shallow enough to stop runaway recursion (e.g. bind mounts). */
        const val DEFAULT_MAX_DEPTH = 32
    }
}

enum class WalkControl { CONTINUE, STOP }

/** [skippedDirectories] could not be listed (permissions, vanished folders) and were left out. */
data class WalkResult(val skippedDirectories: Int, val stopped: Boolean)

/**
 * Breadth-first traversal of a [VirtualFileSystem], so shallow matches are reported first.
 * Symbolic links to directories are reported but never entered, which rules out link cycles.
 * Cancelling the calling coroutine stops the walk at the next entry.
 */
object TreeWalker {
    /**
     * Failing to list [root] itself throws; failures further down are counted in [WalkResult] instead,
     * since one unreadable folder should not abort a search.
     */
    suspend fun walk(
        fs: VirtualFileSystem,
        root: String,
        options: WalkOptions = WalkOptions(),
        visit: suspend (WalkEntry) -> WalkControl,
    ): WalkResult {
        val rootPath = VfsPaths.normalize(root)
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.addLast(rootPath to 0)
        var skipped = 0
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            currentCoroutineContext().ensureActive()
            val children = try {
                fs.list(dir)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (dir == rootPath) throw e
                skipped++
                continue
            }
            for (child in children) {
                currentCoroutineContext().ensureActive()
                if (!options.includeHidden && child.isHidden) continue
                val relative = VfsPaths.relativeSegments(rootPath, child.path).joinToString("/")
                if (visit(WalkEntry(child, relative, depth + 1)) == WalkControl.STOP) {
                    return WalkResult(skipped, stopped = true)
                }
                if (child.isDirectory && !child.isSymlink && depth + 1 < options.maxDepth) {
                    queue.addLast(child.path to depth + 1)
                }
            }
        }
        return WalkResult(skipped, stopped = false)
    }
}
