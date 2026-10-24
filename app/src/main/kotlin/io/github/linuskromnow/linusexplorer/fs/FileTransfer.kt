package io.github.linuskromnow.linusexplorer.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID

enum class ConflictResolution {
    /** Overwrite a file; for two folders this merges them (asking again for clashes inside). */
    REPLACE,
    SKIP,
    /** Keep the existing item and give the new one a " (1)" style name. */
    KEEP_BOTH,
}

/** Decides what happens when [source] would land on an [existing] item of the same name. */
fun interface ConflictResolver {
    suspend fun resolve(source: FileEntry, existing: FileEntry): ConflictResolution

    companion object {
        val KEEP_BOTH = ConflictResolver { _, _ -> ConflictResolution.KEEP_BOTH }
    }
}

/**
 * Copies or moves files between any two registered file systems (local -> SFTP, archive -> local, ...).
 * Name clashes are settled by a [ConflictResolver]; copying into the source's own folder always
 * makes a numbered duplicate.
 */
class FileTransfer(private val registry: FileSystemRegistry) {

    /** Returns the number of top-level items transferred (skipped ones excluded). Cancel the coroutine to abort. */
    suspend fun run(
        sources: List<Location>,
        targetDir: Location,
        move: Boolean,
        conflicts: ConflictResolver = ConflictResolver.KEEP_BOTH,
        onProgress: (TransferProgress) -> Unit,
    ): Int {
        val targetFs = registry[targetDir.fsId]
        if (targetFs.isReadOnly) throw ReadOnlyFsException()
        val target = VfsPaths.normalize(targetDir.path)
        for (source in sources) {
            if (source.fsId == targetDir.fsId && VfsPaths.isAncestorOrSelf(source.path, target) && VfsPaths.parent(source.path) != target) {
                throw FsException("Cannot put “${VfsPaths.name(source.path)}” inside itself")
            }
            if (move && registry[source.fsId].isReadOnly) throw ReadOnlyFsException()
        }
        val destination = Destination(targetFs, targetDir.fsId, conflicts)

        var done = 0
        val planned = mutableListOf<Planned>()
        for (source in sources) {
            val fs = registry[source.fsId]
            val entry = fs.stat(source.path)
            val sameFs = source.fsId == targetDir.fsId
            if (move && sameFs && VfsPaths.parent(entry.path) == target) {
                done++ // moving into the folder it is already in
                continue
            }
            val placement = destination.place(source.fsId, entry, target)
            if (placement is Placement.Skip) continue
            if (move && sameFs && placement is Placement.At) {
                if (placement.replace) targetFs.delete(placement.path)
                if (fs.moveWithin(entry.path, placement.path)) {
                    done++
                    continue
                }
                planned += Planned(source.fsId, fs, entry, Placement.At(placement.path, replace = false))
                continue
            }
            planned += Planned(source.fsId, fs, entry, placement)
        }
        if (planned.isEmpty()) return done

        val totals = TransferTotals()
        for (item in planned) countTree(item.fs, item.entry, totals)
        val tracker = ProgressTracker(totals.files, totals.bytes, onProgress)

        for (item in planned) {
            val complete = destination.copy(item.fsId, item.fs, item.entry, item.placement, tracker)
            if (move && complete) item.fs.delete(item.entry.path) // keep sources that were partly skipped
            done++
        }
        return done
    }

    private data class Planned(val fsId: String, val fs: VirtualFileSystem, val entry: FileEntry, val placement: Placement)

    private sealed interface Placement {
        data class At(val path: String, val replace: Boolean) : Placement
        data class Merge(val path: String) : Placement
        data object Skip : Placement
    }

    /** Everything about the target side of one transfer. */
    private class Destination(
        val fs: VirtualFileSystem,
        val fsId: String,
        val conflicts: ConflictResolver,
    ) {
        suspend fun place(sourceFsId: String, entry: FileEntry, dir: String): Placement {
            val wanted = VfsPaths.child(dir, entry.name)
            if (!fs.exists(wanted)) return Placement.At(wanted, replace = false)
            if (sourceFsId == fsId && wanted == VfsPaths.normalize(entry.path)) {
                return Placement.At(VfsPaths.child(dir, uniqueName(fs, dir, entry.name, entry.isDirectory)), replace = false)
            }
            val existing = fs.stat(wanted)
            return when (conflicts.resolve(entry, existing)) {
                ConflictResolution.SKIP -> Placement.Skip
                ConflictResolution.KEEP_BOTH ->
                    Placement.At(VfsPaths.child(dir, uniqueName(fs, dir, entry.name, entry.isDirectory)), replace = false)
                ConflictResolution.REPLACE ->
                    if (entry.isDirectory && existing.isDirectory) Placement.Merge(wanted) else Placement.At(wanted, replace = true)
            }
        }

        /** Returns true if [entry] was copied completely, i.e. nothing inside it was skipped. */
        suspend fun copy(
            sourceFsId: String,
            sourceFs: VirtualFileSystem,
            entry: FileEntry,
            placement: Placement,
            tracker: ProgressTracker,
        ): Boolean {
            currentCoroutineContext().ensureActive()
            return when (placement) {
                Placement.Skip -> {
                    val skipped = TransferTotals().also { countTree(sourceFs, entry, it) }
                    tracker.skip(skipped.files, skipped.bytes)
                    false
                }
                is Placement.At -> if (!entry.isDirectory) {
                    copyFile(sourceFs, entry, placement.path, placement.replace, tracker)
                    true
                } else {
                    if (placement.replace) fs.delete(placement.path) // a file is replaced by a folder
                    fs.createDirectory(placement.path)
                    copyChildren(sourceFsId, sourceFs, entry, placement.path, tracker)
                }
                is Placement.Merge -> copyChildren(sourceFsId, sourceFs, entry, placement.path, tracker)
            }
        }

        private suspend fun copyChildren(
            sourceFsId: String,
            sourceFs: VirtualFileSystem,
            dir: FileEntry,
            destDir: String,
            tracker: ProgressTracker,
        ): Boolean {
            if (dir.isSymlink) return true // never follow directory links; avoids cycles
            var complete = true
            for (child in sourceFs.list(dir.path)) {
                val placement = place(sourceFsId, child, destDir)
                complete = copy(sourceFsId, sourceFs, child, placement, tracker) && complete
            }
            return complete
        }

        /** With [replace], writes to a temporary name first so a failed copy never destroys the old file. */
        private suspend fun copyFile(
            sourceFs: VirtualFileSystem,
            entry: FileEntry,
            dest: String,
            replace: Boolean,
            tracker: ProgressTracker,
        ) = withContext(Dispatchers.IO) {
            val writeTo = if (replace) VfsPaths.child(VfsPaths.parent(dest)!!, ".${entry.name}.${UUID.randomUUID()}.part") else dest
            try {
                fs.openOutput(writeTo).use { output -> copyFileContents(sourceFs, entry, output, tracker) }
            } catch (t: Throwable) {
                withContext(NonCancellable) { runCatching { fs.delete(writeTo) } } // drop the partial file
                throw t
            }
            if (replace) {
                fs.delete(dest)
                fs.rename(writeTo, dest)
            }
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
