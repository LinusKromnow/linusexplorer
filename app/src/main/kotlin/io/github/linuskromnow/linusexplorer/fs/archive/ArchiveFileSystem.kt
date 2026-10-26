package io.github.linuskromnow.linusexplorer.fs.archive

import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.PasswordProtected
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.RangeReadable
import io.github.linuskromnow.linusexplorer.fs.RangeUnsupportedFsException
import io.github.linuskromnow.linusexplorer.fs.ReadOnlyFsException
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Where an archive's bytes come from. */
sealed interface ArchiveSource {
    val name: String

    /** An archive file on the device (or a temporary local copy). */
    class Local(val file: File) : ArchiveSource {
        override val name: String get() = file.name
        internal val snapshot = file.lastModified() to file.length()
    }

    /** A ZIP on a server, read in pieces; [size] and [lastModified] are from when it was opened. */
    class Remote(
        val fs: RangeReadable,
        val path: String,
        val size: Long,
        val lastModified: Long,
    ) : ArchiveSource {
        override val name: String get() = VfsPaths.name(path)
    }
}

/**
 * Read-only view of an archive. Listing reads only the table of contents; a file is decompressed
 * when it is opened. Extracting is simply copying out of it with
 * [io.github.linuskromnow.linusexplorer.fs.FileTransfer].
 */
class ArchiveFileSystem(
    override val id: String,
    val source: ArchiveSource,
    val format: ArchiveFormat,
) : VirtualFileSystem, PasswordProtected, Closeable {
    constructor(id: String, file: File, format: ArchiveFormat) : this(id, ArchiveSource.Local(file), format)

    override val displayName: String = source.name
    override val rootPath = VfsPaths.ROOT
    override val isReadOnly = true

    private val mutex = Mutex()

    @Volatile private var password: CharArray? = null
    @Volatile private var index: ArchiveIndex? = null
    @Volatile private var handle: ArchiveHandle? = null

    /** False once the archive changed since it was opened (the listing would be stale). */
    fun isCurrent(entry: FileEntry): Boolean = when (val src = source) {
        is ArchiveSource.Local -> (src.file.lastModified() to src.file.length()) == src.snapshot
        is ArchiveSource.Remote -> entry.size == src.size && entry.lastModified == src.lastModified
    }

    override fun setPassword(password: CharArray) {
        this.password = password
        reset() // 7z archives with encrypted headers can only be listed with the password
    }

    override suspend fun list(path: String): List<FileEntry> =
        index().children[VfsPaths.normalize(path)] ?: throw NotFoundFsException(path)

    override suspend fun stat(path: String): FileEntry =
        index().entries[VfsPaths.normalize(path)] ?: throw NotFoundFsException(path)

    override suspend fun exists(path: String): Boolean = VfsPaths.normalize(path) in index().entries

    override suspend fun openInput(path: String): InputStream {
        val rawName = index().rawNames[VfsPaths.normalize(path)] ?: throw NotFoundFsException(path)
        val current = checkNotNull(handle) { "Archive was closed" } // index() just opened it
        return io { current.open(rawName) }
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

    /** Releases the open archive (file handle, cached network blocks); it reopens on the next use. */
    override fun close() = reset()

    private fun reset() {
        index = null
        handle?.let { runCatching { it.close() } }
        handle = null
    }

    private suspend fun index(): ArchiveIndex = index ?: mutex.withLock {
        index ?: io {
            val (opened, raw) = openHandle()
            handle = opened
            ArchiveIndex.build(raw)
        }.also { index = it }
    }

    /** Opens the archive once and returns it with its entries. */
    private fun openHandle(): Pair<ArchiveHandle, List<RawEntry>> = when (val src = source) {
        is ArchiveSource.Local -> if (format == ArchiveFormat.ZIP) openLocalZip(src.file) else {
            StatelessHandle(ArchiveReader.forFormat(format), src.file, password).let { it to it.list() }
        }
        is ArchiveSource.Remote -> {
            val channel = RangeChannel(src.size) { offset, length -> runBlocking { src.fs.readRange(src.path, offset, length) } }
            val zip = SeekableZipHandle.open(channel)
            val raw = zip.list()
            if (raw.any { it.encrypted }) {
                zip.close()
                throw RangeUnsupportedFsException("Encrypted ZIPs on a server are downloaded first")
            }
            zip to raw
        }
    }

    /** Commons Compress keeps the ZIP open and indexed; only zip4j can decrypt, so encrypted ZIPs use that. */
    private fun openLocalZip(file: File): Pair<ArchiveHandle, List<RawEntry>> {
        val seekable = try {
            SeekableZipHandle.open(file)
        } catch (e: IOException) {
            null // something only zip4j understands (e.g. split archives): let it try
        }
        if (seekable != null) {
            val raw = seekable.list()
            if (raw.none { it.encrypted }) return seekable to raw
            seekable.close()
        }
        return StatelessHandle(ZipReader, file, password).let { it to it.list() }
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
            throw FsException("Cannot read ${source.name}: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    companion object {
        private const val PREFIX = "archive:"

        fun idFor(sourceFsId: String, path: String) = "$PREFIX$sourceFsId:$path"

        /** Whether [fsId] is an archive read straight from a network connection. */
        fun isOnNetwork(fsId: String) = fsId.startsWith(PREFIX + Connection.fsIdFor(""))
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
