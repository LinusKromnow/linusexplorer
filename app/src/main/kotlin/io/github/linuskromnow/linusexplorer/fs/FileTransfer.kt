package io.github.linuskromnow.linusexplorer.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Copies or moves files between any two registered file systems (local -> SFTP, SMB -> local, ...).
 * Name clashes in the target folder are resolved by appending " (1)", " (2)", ...
 */
class FileTransfer(private val registry: FileSystemRegistry) {

    data class Progress(
        val filesDone: Int,
        val filesTotal: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        val currentName: String,
    ) {
        val fraction: Float
            get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
    }

    /** Returns the number of top-level items transferred. Cancel the calling coroutine to abort. */
    suspend fun run(
        sources: List<Location>,
        targetDir: Location,
        move: Boolean,
        onProgress: (Progress) -> Unit,
    ): Int {
        val targetFs = registry[targetDir.fsId]
        for (source in sources) {
            if (source.fsId == targetDir.fsId && VfsPaths.isAncestorOrSelf(source.path, targetDir.path)) {
                throw FsException("Cannot put “${VfsPaths.name(source.path)}” inside itself")
            }
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
        val totals = Totals()
        for ((source, entry) in plan) count(registry[source.fsId], entry, totals)
        val tracker = Tracker(totals.files, totals.bytes, onProgress)

        for ((source, entry) in plan) {
            val sourceFs = registry[source.fsId]
            val destName = uniqueName(targetFs, targetDir.path, entry.name, entry.isDirectory)
            copyTree(sourceFs, entry, targetFs, VfsPaths.child(targetDir.path, destName), tracker)
            if (move) sourceFs.delete(entry.path)
            done++
        }
        return done
    }

    private suspend fun count(fs: VirtualFileSystem, entry: FileEntry, totals: Totals) {
        currentCoroutineContext().ensureActive()
        if (!entry.isDirectory) {
            totals.files++
            totals.bytes += entry.size
        } else if (!entry.isSymlink) {
            for (child in fs.list(entry.path)) count(fs, child, totals)
        }
    }

    private suspend fun copyTree(
        sourceFs: VirtualFileSystem,
        entry: FileEntry,
        targetFs: VirtualFileSystem,
        destPath: String,
        tracker: Tracker,
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
        tracker: Tracker,
    ) = withContext(Dispatchers.IO) {
        tracker.startFile(entry.name)
        try {
            sourceFs.openInput(entry.path).use { input ->
                targetFs.openOutput(destPath).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        tracker.addBytes(read)
                    }
                }
            }
        } catch (t: Throwable) {
            withContext(NonCancellable) { runCatching { targetFs.delete(destPath) } } // drop the partial file
            throw t
        }
        tracker.finishFile()
    }

    private class Totals {
        var files = 0
        var bytes = 0L
    }

    private class Tracker(
        private val filesTotal: Int,
        private val bytesTotal: Long,
        private val onProgress: (Progress) -> Unit,
    ) {
        private var filesDone = 0
        private var bytesDone = 0L
        private var current = ""

        fun startFile(name: String) {
            current = name
            emit()
        }

        fun addBytes(count: Int) {
            bytesDone += count
            emit()
        }

        fun finishFile() {
            filesDone++
            emit()
        }

        private fun emit() = onProgress(Progress(filesDone, filesTotal, bytesDone, bytesTotal, current))
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

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
