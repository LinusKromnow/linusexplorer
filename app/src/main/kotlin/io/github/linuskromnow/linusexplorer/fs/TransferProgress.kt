package io.github.linuskromnow.linusexplorer.fs

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.OutputStream

data class TransferProgress(
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentName: String,
) {
    val fraction: Float
        get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
}

internal class TransferTotals {
    var files = 0
    var bytes = 0L
}

/** Adds the files and bytes below [entry] to [totals]. Directory symlinks are not followed. */
internal suspend fun countTree(fs: VirtualFileSystem, entry: FileEntry, totals: TransferTotals) {
    currentCoroutineContext().ensureActive()
    if (!entry.isDirectory) {
        totals.files++
        totals.bytes += entry.size
    } else if (!entry.isSymlink) {
        for (child in fs.list(entry.path)) countTree(fs, child, totals)
    }
}

internal class ProgressTracker(
    private val filesTotal: Int,
    private val bytesTotal: Long,
    private val onProgress: (TransferProgress) -> Unit,
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

    /** Counts files that were skipped as done so the progress bar still reaches the end. */
    fun skip(files: Int, bytes: Long) {
        filesDone += files
        bytesDone += bytes
        emit()
    }

    private fun emit() = onProgress(TransferProgress(filesDone, filesTotal, bytesDone, bytesTotal, current))
}

internal const val COPY_BUFFER_SIZE = 64 * 1024

/** Streams one file into [output] (left open), reporting to [tracker]; honours cancellation. */
internal suspend fun copyFileContents(
    fs: VirtualFileSystem,
    entry: FileEntry,
    output: OutputStream,
    tracker: ProgressTracker,
) {
    tracker.startFile(entry.name)
    fs.openInput(entry.path).use { input ->
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            tracker.addBytes(read)
        }
    }
    tracker.finishFile()
}
