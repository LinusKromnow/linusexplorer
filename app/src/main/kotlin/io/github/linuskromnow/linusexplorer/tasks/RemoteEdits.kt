package io.github.linuskromnow.linusexplorer.tasks

import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.Location
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A remote file handed to another app as a local copy. [localModified] and [localSize] describe
 * the copy as last synced, so a later difference means the other app saved changes.
 */
data class RemoteEdit(
    val source: Location,
    val localPath: String,
    val localModified: Long,
    val localSize: Long,
    /** The remote file's modification time when it was downloaded or last uploaded; 0 if unknown. */
    val remoteModified: Long,
) {
    val name: String get() = File(localPath).name

    fun isChanged(): Boolean {
        val file = File(localPath)
        return file.isFile && (file.lastModified() != localModified || file.length() != localSize)
    }
}

/**
 * Remote files opened in other apps, kept on disk so edits survive Android killing the app while
 * the user is in the editor.
 */
class RemoteEdits(private val file: File) {
    private var edits: List<RemoteEdit> = load()

    @Synchronized
    fun track(source: Location, local: File, remoteModified: Long) {
        val edit = RemoteEdit(source, local.path, local.lastModified(), local.length(), remoteModified)
        write(edits.filterNot { it.localPath == local.path } + edit)
    }

    /** Copies with changes nobody uploaded or discarded yet. */
    @Synchronized
    fun changed(): List<RemoteEdit> = edits.filter { it.isChanged() }

    /** The copy now matches the remote file; later edits in the other app are offered again. */
    @Synchronized
    fun markUploaded(edit: RemoteEdit, remoteModified: Long) {
        val local = File(edit.localPath)
        val synced = edit.copy(localModified = local.lastModified(), localSize = local.length(), remoteModified = remoteModified)
        write(edits.map { if (it.localPath == edit.localPath) synced else it })
    }

    @Synchronized
    fun forget(edit: RemoteEdit) = write(edits.filterNot { it.localPath == edit.localPath })

    /**
     * Clears the folder of copies handed to other apps (one sub-folder per copy) but keeps copies
     * with unsaved changes, and, when [keepUnchanged], every tracked copy; the rest is forgotten.
     */
    @Synchronized
    fun cleanCache(openCache: File, keepUnchanged: Boolean) {
        val keep = edits.filter { (keepUnchanged || it.isChanged()) && File(it.localPath).exists() }
        val keepDirs = keep.mapNotNullTo(HashSet()) { File(it.localPath).parentFile?.absoluteFile }
        openCache.listFiles().orEmpty().filter { it.absoluteFile !in keepDirs }.forEach { it.deleteRecursively() }
        if (keep != edits) write(keep)
    }

    private fun write(list: List<RemoteEdit>) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeBytes(RemoteEditsCodec.encode(list))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        edits = list
    }

    private fun load(): List<RemoteEdit> {
        if (!file.exists()) return emptyList()
        return try {
            RemoteEditsCodec.decode(file.readBytes())
        } catch (e: Exception) {
            file.delete()
            emptyList()
        }
    }
}

internal object RemoteEditsCodec {
    private const val MAGIC = 0x4C455245 // "LERE"
    private const val VERSION = 1

    fun encode(edits: List<RemoteEdit>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(edits.size)
            for (edit in edits) {
                out.writeUTF(edit.source.fsId)
                out.writeUTF(edit.source.path)
                out.writeUTF(edit.localPath)
                out.writeLong(edit.localModified)
                out.writeLong(edit.localSize)
                out.writeLong(edit.remoteModified)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): List<RemoteEdit> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (input.readInt() != MAGIC) throw FsException("Pending uploads are damaged")
        if (input.readInt() > VERSION) throw FsException("Pending uploads need a newer LinusExplorer")
        List(input.readInt()) {
            RemoteEdit(
                source = Location(input.readUTF(), input.readUTF()),
                localPath = input.readUTF(),
                localModified = input.readLong(),
                localSize = input.readLong(),
                remoteModified = input.readLong(),
            )
        }
    }
}
