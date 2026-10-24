package io.github.linuskromnow.linusexplorer.fs

import io.github.linuskromnow.linusexplorer.crypto.Age
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Encrypts single files to `name.age` and back, on any [VirtualFileSystem]. */
object FileEncryption {

    fun isEncrypted(name: String) = name.endsWith(Age.EXTENSION, ignoreCase = true) && name.length > Age.EXTENSION.length

    /** Encrypts each file next to itself; returns how many were encrypted. */
    suspend fun encrypt(
        fs: VirtualFileSystem,
        files: List<FileEntry>,
        password: CharArray,
        deleteOriginals: Boolean,
        onProgress: (TransferProgress) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val tracker = ProgressTracker(files.size, files.sumOf { it.size }, onProgress)
        for (file in files) {
            require(!file.isDirectory) { "Folders cannot be encrypted directly" }
            val dir = VfsPaths.parent(file.path)!!
            val target = VfsPaths.child(dir, FileTransfer.uniqueName(fs, dir, file.name + Age.EXTENSION, isDirectory = false))
            writeOrDelete(fs, target) { output ->
                Age.encrypt(output, password).use { encrypted -> copyFileContents(fs, file, encrypted, tracker) }
            }
            if (deleteOriginals) fs.delete(file.path)
        }
        files.size
    }

    /**
     * Decrypts [file] into [targetDir] on [targetFs] and returns the new file's path.
     * Throws [io.github.linuskromnow.linusexplorer.crypto.AgeNoMatchException] for a wrong password.
     */
    suspend fun decrypt(
        fs: VirtualFileSystem,
        file: FileEntry,
        targetFs: VirtualFileSystem,
        targetDir: String,
        password: CharArray,
        onProgress: (TransferProgress) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val plainName = file.name.dropLast(Age.EXTENSION.length).ifEmpty { "decrypted" }
        val target = VfsPaths.child(targetDir, FileTransfer.uniqueName(targetFs, targetDir, plainName, isDirectory = false))
        val tracker = ProgressTracker(1, file.size, onProgress)
        tracker.startFile(plainName)
        writeOrDelete(targetFs, target) { output ->
            fs.openInput(file.path).buffered().use { raw ->
                Age.decrypt(raw, password).use { plain ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = plain.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        tracker.addBytes(read)
                    }
                }
            }
        }
        tracker.finishFile()
        target
    }

    /** Runs [write] against a new file at [path], deleting it again if anything goes wrong. */
    private suspend fun writeOrDelete(fs: VirtualFileSystem, path: String, write: suspend (OutputStream) -> Unit) {
        try {
            fs.openOutput(path).use { write(it) }
        } catch (t: Throwable) {
            withContext(NonCancellable) { runCatching { fs.delete(path) } }
            throw t
        }
    }
}
