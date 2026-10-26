package io.github.linuskromnow.linusexplorer.fs.trash

import io.github.linuskromnow.linusexplorer.fs.FsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Something in the recycle bin. [file] is the trashed copy, still under its original name. */
data class TrashedItem(
    val id: String,
    val originalPath: String,
    val deletedAt: Long,
    val isDirectory: Boolean,
    /** Bytes for files; 0 for folders (measuring them on every listing would be slow). */
    val size: Long,
    val file: File,
) {
    val name: String get() = file.name
}

/**
 * Recycle bin for local files.
 *
 * Every storage volume gets its own hidden bin folder, so trashing is a rename on the same
 * file system instead of a copy, however big the files are:
 *
 * ```
 * <volume>/.LinusExplorerTrash/files/<id>/<original name>
 * <volume>/.LinusExplorerTrash/info/<id>      original path + deletion time
 * ```
 *
 * Files outside any volume (e.g. browsing "/" as root) can't be trashed and are deleted for good.
 */
class RecycleBin(
    /** Mount points of the storage volumes, e.g. "/storage/emulated/0". */
    private val volumeRoots: () -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _restores = MutableStateFlow(0)

    /** Counts restores, so folder listings that may now be missing a file know to reload. */
    val restores: StateFlow<Int> = _restores.asStateFlow()

    /** Whether [path] would go to the bin (rather than being deleted outright). */
    fun accepts(path: String): Boolean {
        val target = pathOf(path)
        val volume = volumeOf(target) ?: return false
        return target != volume && !target.startsWith(volume.resolve(BIN_NAME))
    }

    /** Moves [path] into its volume's bin; returns false if the bin can't take it. */
    suspend fun trash(path: String): Boolean = io {
        if (!accepts(path)) return@io false
        val source = File(path)
        if (!Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) throw FsException("“${source.name}” does not exist")
        val bin = binDir(volumeOf(pathOf(path))!!)
        val id = UUID.randomUUID().toString()
        val holder = File(File(bin, FILES), id)
        if (!holder.mkdirs()) throw FsException("Can't create the recycle bin in ${bin.parent}")
        File(bin, NOMEDIA).takeIf { !it.exists() }?.createNewFile() // keep trashed photos out of galleries
        val info = File(File(bin, INFO).apply { mkdirs() }, id)
        val target = File(holder, source.name)
        info.writeBytes(encodeInfo(pathOf(path).toString(), clock()))
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: Exception) {
            info.delete()
            holder.delete()
            if (e is AtomicMoveNotSupportedException) false else throw e
        }
    }

    /** Everything in all bins, newest first. Leftovers without readable info are skipped. */
    suspend fun list(): List<TrashedItem> = io {
        volumeRoots().flatMap { volume ->
            val bin = binDir(pathOf(volume))
            File(bin, INFO).listFiles().orEmpty().mapNotNull { info -> readItem(bin, info) }
        }.sortedByDescending { it.deletedAt }
    }

    /**
     * Puts [item] back where it came from, recreating missing parent folders. If something new
     * took its place meanwhile, the restored copy gets a " (1)"-style suffix. Returns the new path.
     */
    suspend fun restore(item: TrashedItem): String = io {
        val original = File(item.originalPath)
        original.parentFile?.mkdirs()
        val target = freeName(original, item.isDirectory)
        Files.move(item.file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        removeRecord(item)
        _restores.value++
        target.path
    }

    suspend fun delete(item: TrashedItem) = io {
        deleteTree(item.file.toPath())
        removeRecord(item)
    }

    suspend fun empty() = io {
        for (volume in volumeRoots()) {
            val bin = binDir(pathOf(volume))
            File(bin, FILES).listFiles().orEmpty().forEach { deleteTree(it.toPath()) }
            File(bin, INFO).listFiles().orEmpty().forEach { it.delete() }
        }
    }

    /** Deletes what has been in the bin for longer than [maxAgeDays]. Returns how many items went. */
    suspend fun purgeOlderThan(maxAgeDays: Int = RETENTION_DAYS): Int {
        val cutoff = clock() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong())
        val expired = list().filter { it.deletedAt < cutoff }
        expired.forEach { delete(it) }
        return expired.size
    }

    private fun readItem(bin: File, info: File): TrashedItem? {
        val (originalPath, deletedAt) = try {
            decodeInfo(info.readBytes())
        } catch (e: IOException) {
            return null
        }
        val file = File(File(bin, FILES), info.name).listFiles()?.singleOrNull() ?: return null
        val directory = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        return TrashedItem(info.name, originalPath, deletedAt, directory, if (directory) 0 else file.length(), file)
    }

    private fun removeRecord(item: TrashedItem) {
        item.file.parentFile?.delete()
        File(File(item.file.parentFile?.parentFile?.parentFile, INFO), item.id).delete()
    }

    private fun freeName(original: File, isDirectory: Boolean): File {
        if (!original.exists()) return original
        val dot = original.name.lastIndexOf('.').takeIf { it > 0 && !isDirectory } ?: original.name.length
        val base = original.name.substring(0, dot)
        val ext = original.name.substring(dot)
        return generateSequence(1) { it + 1 }.map { File(original.parentFile, "$base ($it)$ext") }.first { !it.exists() }
    }

    /** Host paths rather than VfsPaths, so the tests also pass on Windows. */
    private fun pathOf(path: String): Path = Paths.get(path).toAbsolutePath().normalize()

    /** The innermost volume holding [path]; never "/" itself, which would put a bin on the system partition. */
    private fun volumeOf(path: Path): Path? =
        volumeRoots().map(::pathOf).filter { path.startsWith(it) && it.parent != null }.maxByOrNull { it.nameCount }

    private fun binDir(volume: Path) = volume.resolve(BIN_NAME).toFile()

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(path).use { children -> children.forEach(::deleteTree) }
        }
        Files.delete(path)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: FsException) {
            throw e
        } catch (e: IOException) {
            throw FsException(e.message ?: "Recycle bin error", e)
        }
    }

    companion object {
        const val BIN_NAME = ".LinusExplorerTrash"
        const val RETENTION_DAYS = 30
        private const val FILES = "files"
        private const val INFO = "info"
        private const val NOMEDIA = ".nomedia"
        private const val INFO_VERSION = 1

        internal fun encodeInfo(originalPath: String, deletedAt: Long): ByteArray =
            ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeInt(INFO_VERSION)
                    out.writeUTF(originalPath)
                    out.writeLong(deletedAt)
                }
            }.toByteArray()

        internal fun decodeInfo(bytes: ByteArray): Pair<String, Long> = DataInputStream(bytes.inputStream()).use { input ->
            if (input.readInt() != INFO_VERSION) throw IOException("Unknown recycle bin record")
            input.readUTF() to input.readLong()
        }
    }
}
