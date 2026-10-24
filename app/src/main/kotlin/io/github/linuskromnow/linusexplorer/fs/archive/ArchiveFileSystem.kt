package io.github.linuskromnow.linusexplorer.fs.archive

import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.PasswordProtected
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.ReadOnlyFsException
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Read-only view of an archive file on local disk. Extracting is simply copying out of it with
 * [io.github.linuskromnow.linusexplorer.fs.FileTransfer].
 */
class ArchiveFileSystem(
    override val id: String,
    val file: File,
    val format: ArchiveFormat,
) : VirtualFileSystem, PasswordProtected {
    override val displayName: String = file.name
    override val rootPath = VfsPaths.ROOT
    override val isReadOnly = true

    private val reader = ArchiveReader.forFormat(format)
    private val mutex = Mutex()
    private val snapshot = file.lastModified() to file.length()

    @Volatile private var password: CharArray? = null
    @Volatile private var index: ArchiveIndex? = null

    /** False once the archive file was modified after this instance was created. */
    val isCurrent: Boolean get() = (file.lastModified() to file.length()) == snapshot

    override fun setPassword(password: CharArray) {
        this.password = password
        index = null // 7z archives with encrypted headers can only be listed with the password
    }

    override suspend fun list(path: String): List<FileEntry> =
        index().children[VfsPaths.normalize(path)] ?: throw NotFoundFsException(path)

    override suspend fun stat(path: String): FileEntry =
        index().entries[VfsPaths.normalize(path)] ?: throw NotFoundFsException(path)

    override suspend fun exists(path: String): Boolean = VfsPaths.normalize(path) in index().entries

    override suspend fun openInput(path: String): InputStream {
        val rawName = index().rawNames[VfsPaths.normalize(path)] ?: throw NotFoundFsException(path)
        return io { reader.open(file, password, rawName) }
    }

    /** Throws [PasswordRequiredFsException] unless encrypted entries can be read with the current password. */
    suspend fun verifyPassword() {
        val path = index().firstEncrypted ?: return
        openInput(path).use { input ->
            io {
                val buffer = ByteArray(64 * 1024)
                input.read(buffer) // wrong 7z keys usually fail within the first block
            }
        }
    }

    override suspend fun createDirectory(path: String) = throw ReadOnlyFsException()
    override suspend fun createFile(path: String) = throw ReadOnlyFsException()
    override suspend fun delete(path: String) = throw ReadOnlyFsException()
    override suspend fun rename(path: String, newPath: String) = throw ReadOnlyFsException()
    override suspend fun openOutput(path: String): OutputStream = throw ReadOnlyFsException()

    private suspend fun index(): ArchiveIndex = index ?: mutex.withLock {
        index ?: io { ArchiveIndex.build(reader.list(file, password)) }.also { index = it }
    }

    /** Runs blocking archive I/O off the main thread, tagging password errors with this file system's id. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: PasswordRequiredFsException) {
            throw PasswordRequiredFsException(id, e.wrongPassword, e)
        } catch (e: FsException) {
            throw e
        } catch (e: IOException) {
            throw FsException("Cannot read ${file.name}: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    companion object {
        fun idFor(sourceFsId: String, path: String) = "archive:$sourceFsId:$path"
    }
}

/** Directory tree built from an archive's flat entry list. Paths are normalized, so "../" cannot escape. */
internal class ArchiveIndex private constructor(
    val entries: Map<String, FileEntry>,
    val children: Map<String, List<FileEntry>>,
    val rawNames: Map<String, String>,
    val firstEncrypted: String?,
) {
    companion object {
        fun build(raw: List<RawEntry>): ArchiveIndex {
            val entries = LinkedHashMap<String, FileEntry>()
            val rawNames = HashMap<String, String>()
            var firstEncrypted: String? = null
            entries[VfsPaths.ROOT] = directory(VfsPaths.ROOT, 0)

            fun ensureDirectory(path: String) {
                if (entries[path]?.isDirectory == true) return
                ensureDirectory(VfsPaths.parent(path) ?: return)
                entries[path] = directory(path, 0)
            }

            for (entry in raw) {
                val path = VfsPaths.normalize(entry.name.replace('\\', '/'))
                if (path == VfsPaths.ROOT) continue
                ensureDirectory(VfsPaths.parent(path)!!)
                if (entry.isDirectory) {
                    entries[path] = directory(path, entry.lastModified)
                } else {
                    entries[path] = FileEntry(
                        name = VfsPaths.name(path),
                        path = path,
                        isDirectory = false,
                        size = entry.size.coerceAtLeast(0),
                        lastModified = entry.lastModified.coerceAtLeast(0),
                    )
                    rawNames[path] = entry.name
                    if (entry.encrypted && firstEncrypted == null) firstEncrypted = path
                }
            }
            val children = entries.values
                .filter { it.path != VfsPaths.ROOT }
                .groupBy { VfsPaths.parent(it.path)!! }
            val withEmptyDirs = entries.values.filter { it.isDirectory }.associate { it.path to (children[it.path] ?: emptyList()) }
            return ArchiveIndex(entries, withEmptyDirs, rawNames, firstEncrypted)
        }

        private fun directory(path: String, lastModified: Long) =
            FileEntry(VfsPaths.name(path), path, isDirectory = true, size = 0, lastModified = lastModified.coerceAtLeast(0))
    }
}
