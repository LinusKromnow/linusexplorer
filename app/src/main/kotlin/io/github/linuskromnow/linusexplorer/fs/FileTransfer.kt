package io.github.linuskromnow.linusexplorer.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Copies or moves files between any two registered file systems (local -> SFTP, archive -> local, ...).
 * Name clashes in the target folder are resolved by appending " (1)", " (2)", ...
 */
class FileTransfer(private val registry: FileSystemRegistry) {

    /** Returns the number of top-level items transferred. Cancel the calling coroutine to abort. */
    suspend fun run(
        sources: List<Location>,
        targetDir: Location,
        move: Boolean,
        onProgress: (TransferProgress) -> Unit,
    ): Int {
        val targetFs = registry[targetDir.fsId]
        if (targetFs.isReadOnly) throw ReadOnlyFsException()
        for (source in sources) {
            if (source.fsId == targetDir.fsId && VfsPaths.isAncestorOrSelf(source.path, targetDir.path)) {
                throw FsException("Cannot put “${VfsPaths.name(source.path)}” inside itself")
            }
            if (move && registry[source.fsId].isReadOnly) throw ReadOnlyFsException()
        }

        var done = 0
        val needsCopy = mutableListOf<Location>()
        for (source in sources) {
            if (!move || source.fsId != targetDir.fsId) {
                needsCopy += source
                continue
            }
            if (VfsPaths.parent(source.path) == VfsPaths.normalize(targetDir.path)) {
                done++ // moving into the folder it is already in
                continue
            }
            val fs = registry[source.fsId]
            val entry = fs.stat(source.path)
            val dest = VfsPaths.child(targetDir.path, uniqueName(targetFs, targetDir.path, entry.name, entry.isDirectory))
            if (fs.moveWithin(source.path, dest)) done++ else needsCopy += source
        }
        if (needsCopy.isEmpty()) return done

        val plan = needsCopy.map { it to registry[it.fsId].stat(it.path) }
        val totals = TransferTotals()
        for ((source, entry) in plan) countTree(registry[source.fsId], entry, totals)
        val tracker = ProgressTracker(totals.files, totals.bytes, onProgress)

        for ((source, entry) in plan) {
            val sourceFs = registry[source.fsId]
            val destName = uniqueName(targetFs, targetDir.path, entry.name, entry.isDirectory)
            copyTree(sourceFs, entry, targetFs, VfsPaths.child(targetDir.path, destName), tracker)
            if (move) sourceFs.delete(entry.path)
            done++
        }
        return done
    }

    private suspend fun copyTree(
        sourceFs: VirtualFileSystem,
        entry: FileEntry,
        targetFs: VirtualFileSystem,
        destPath: String,
        tracker: ProgressTracker,
    ) {
        currentCoroutineContext().ensureActive()
        if (!entry.isDirectory) {
            copyFile(sourceFs, entry, targetFs, destPath, tracker)
            return
        }
        targetFs.createDirectory(destPath)
        if (entry.isSymlink) return // never follow directory links; avoids cycles
        for (child in sourceFs.list(entry.path)) {
            copyTree(sourceFs, child, targetFs, VfsPaths.child(destPath, child.name), tracker)
        }
    }

    private suspend fun copyFile(
        sourceFs: VirtualFileSystem,
        entry: FileEntry,
        targetFs: VirtualFileSystem,
        destPath: String,
        tracker: ProgressTracker,
    ) = withContext(Dispatchers.IO) {
        try {
            targetFs.openOutput(destPath).use { output -> copyFileContents(sourceFs, entry, output, tracker) }
        } catch (t: Throwable) {
            withContext(NonCancellable) { runCatching { targetFs.delete(destPath) } } // drop the partial file
            throw t
        }
    }

    companion object {
        suspend fun uniqueName(fs: VirtualFileSystem, dir: String, name: String, isDirectory: Boolean): String {
            if (!fs.exists(VfsPaths.child(dir, name))) return name
            var n = 1
            while (true) {
                val candidate = numberedName(name, n, isDirectory)
                if (!fs.exists(VfsPaths.child(dir, candidate))) return candidate
                n++
            }
        }

        /** "photo.jpg" -> "photo (2).jpg"; folders and dotfiles get the number at the end. */
        fun numberedName(name: String, n: Int, isDirectory: Boolean): String {
            val dot = name.lastIndexOf('.')
            return if (isDirectory || dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
        }
    }
}
