package io.github.linuskromnow.linusexplorer.fs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Platform-independent [VirtualFileSystem] for tests. */
class InMemoryFileSystem(override val id: String = "mem") : VirtualFileSystem {
    override val displayName = "memory"
    override val rootPath = VfsPaths.ROOT

    private val files = HashMap<String, ByteArray>()
    private val dirs = mutableSetOf(VfsPaths.ROOT)

    fun write(path: String, content: String) {
        mkdirs(VfsPaths.parent(path)!!)
        files[VfsPaths.normalize(path)] = content.toByteArray()
    }

    fun read(path: String): String = String(files.getValue(VfsPaths.normalize(path)))

    fun mkdirs(path: String) {
        var current: String? = VfsPaths.normalize(path)
        while (current != null) {
            dirs += current
            current = VfsPaths.parent(current)
        }
    }

    fun allPaths(): Set<String> = files.keys + dirs

    override suspend fun list(path: String): List<FileEntry> {
        val dir = VfsPaths.normalize(path)
        if (dir !in dirs) throw NotFoundFsException(path)
        return allPaths().filter { it != VfsPaths.ROOT && VfsPaths.parent(it) == dir }.map { stat(it) }
    }

    override suspend fun stat(path: String): FileEntry {
        val p = VfsPaths.normalize(path)
        return when (p) {
            in dirs -> FileEntry(VfsPaths.name(p), p, isDirectory = true, size = 0, lastModified = MTIME)
            in files -> FileEntry(VfsPaths.name(p), p, isDirectory = false, size = files.getValue(p).size.toLong(), lastModified = MTIME)
            else -> throw NotFoundFsException(path)
        }
    }

    override suspend fun exists(path: String) = VfsPaths.normalize(path).let { it in dirs || it in files }

    override suspend fun createDirectory(path: String) {
        if (exists(path)) throw AlreadyExistsFsException(path)
        dirs += VfsPaths.normalize(path)
    }

    override suspend fun createFile(path: String) {
        if (exists(path)) throw AlreadyExistsFsException(path)
        files[VfsPaths.normalize(path)] = ByteArray(0)
    }

    override suspend fun delete(path: String) {
        val p = VfsPaths.normalize(path)
        if (!exists(p)) throw NotFoundFsException(path)
        files.keys.removeAll { VfsPaths.isAncestorOrSelf(p, it) }
        dirs.removeAll { it != VfsPaths.ROOT && VfsPaths.isAncestorOrSelf(p, it) }
    }

    override suspend fun rename(path: String, newPath: String) {
        val from = VfsPaths.normalize(path)
        val to = VfsPaths.normalize(newPath)
        if (exists(to)) throw AlreadyExistsFsException(to)
        fun moved(p: String) = to + p.removePrefix(from)
        files.keys.filter { VfsPaths.isAncestorOrSelf(from, it) }.forEach { files[moved(it)] = files.remove(it)!! }
        dirs.filter { VfsPaths.isAncestorOrSelf(from, it) }.forEach { dirs -= it; dirs += moved(it) }
    }

    override suspend fun openInput(path: String): InputStream =
        ByteArrayInputStream(files[VfsPaths.normalize(path)] ?: throw NotFoundFsException(path))

    override suspend fun openOutput(path: String): OutputStream {
        val p = VfsPaths.normalize(path)
        files[p] = ByteArray(0)
        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                files[p] = toByteArray()
            }
        }
    }

    companion object {
        const val MTIME = 1_700_000_000_000L
    }
}
