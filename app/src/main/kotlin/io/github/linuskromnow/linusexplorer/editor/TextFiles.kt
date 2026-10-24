package io.github.linuskromnow.linusexplorer.editor

import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID

/** A text file as loaded by [TextFiles]; [text] uses "\n" line breaks. */
data class TextDocument(
    val text: String,
    val encoding: TextEncoding,
    val hasBom: Boolean,
    val lineEnding: LineEnding,
    /** Only the first [TextFiles.MAX_EDITABLE_BYTES] were loaded, so saving would lose data. */
    val truncated: Boolean,
)

sealed interface LoadResult {
    data class Text(val document: TextDocument) : LoadResult
    data object Binary : LoadResult
}

/** Loads and saves text files through a [VirtualFileSystem], so it works on every backend. */
object TextFiles {
    const val MAX_EDITABLE_BYTES = 5 * 1024 * 1024
    private const val SNIFF_BYTES = 8192

    suspend fun load(fs: VirtualFileSystem, path: String): LoadResult {
        if (fs.stat(path).isDirectory) throw FsException("“${VfsPaths.name(path)}” is a folder")
        val bytes = readUpTo(fs, path, MAX_EDITABLE_BYTES + 1)
        val truncated = bytes.size > MAX_EDITABLE_BYTES
        val data = if (truncated) bytes.copyOf(MAX_EDITABLE_BYTES) else bytes
        val decoded = TextCodec.decode(data, truncated) ?: return LoadResult.Binary
        return LoadResult.Text(TextDocument(decoded.text, decoded.encoding, decoded.hasBom, decoded.lineEnding, truncated))
    }

    /** Whether the start of the file looks like text; used for files without a telling extension. */
    suspend fun looksLikeText(fs: VirtualFileSystem, path: String): Boolean {
        val head = readUpTo(fs, path, SNIFF_BYTES)
        return !TextCodec.looksBinary(head)
    }

    /**
     * Writes [text] using the encoding, BOM and line endings of [original]. Returns the document
     * as saved: [original] updated with the new text, switched to UTF-8 if [text] no longer
     * fits the old encoding.
     *
     * The file is written to a temporary sibling first and swapped in, so a failed write never
     * destroys the old content. If that isn't possible (symlink, no write access to the folder,
     * ...) the file is overwritten in place.
     */
    suspend fun save(fs: VirtualFileSystem, path: String, text: String, original: TextDocument): TextDocument {
        require(!original.truncated) { "Truncated documents are read-only" }
        val encoding = if (TextCodec.canEncode(text, original.encoding)) original.encoding else TextEncoding.UTF8
        val saved = original.copy(text = text, encoding = encoding)
        val bytes = TextCodec.encode(text, encoding, saved.hasBom, saved.lineEnding)
        if (!replaceAtomically(fs, path, bytes)) writeInPlace(fs, path, bytes)
        return saved
    }

    private suspend fun replaceAtomically(fs: VirtualFileSystem, path: String, bytes: ByteArray): Boolean {
        val current = try {
            fs.stat(path)
        } catch (e: FsException) {
            return false
        }
        if (current.isSymlink) return false
        val dir = VfsPaths.parent(path) ?: return false
        val id = UUID.randomUUID().toString().take(8)
        val temp = VfsPaths.child(dir, ".${VfsPaths.name(path)}.$id.tmp")
        val backup = VfsPaths.child(dir, ".${VfsPaths.name(path)}.$id.bak")
        try {
            writeInPlace(fs, temp, bytes)
        } catch (e: java.io.IOException) {
            runCatching { fs.delete(temp) }
            return false
        }
        // rename() refuses to overwrite, so move the original aside and restore it if anything fails.
        try {
            fs.rename(path, backup)
        } catch (e: java.io.IOException) {
            runCatching { fs.delete(temp) }
            return false
        }
        try {
            fs.rename(temp, path)
        } catch (e: java.io.IOException) {
            runCatching { fs.rename(backup, path) }
            runCatching { fs.delete(temp) }
            return false
        }
        runCatching { fs.delete(backup) }
        return true
    }

    private suspend fun writeInPlace(fs: VirtualFileSystem, path: String, bytes: ByteArray) {
        val output = fs.openOutput(path)
        withContext(Dispatchers.IO) { output.use { it.write(bytes) } }
    }

    private suspend fun readUpTo(fs: VirtualFileSystem, path: String, max: Int): ByteArray {
        val input = fs.openInput(path)
        return withContext(Dispatchers.IO) { input.use { it.readUpTo(max) } }
    }

    private fun InputStream.readUpTo(max: Int): ByteArray {
        val buffer = ByteArray(max.coerceAtMost(64 * 1024))
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < max) {
            val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
