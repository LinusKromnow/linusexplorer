package io.github.linuskromnow.linusexplorer.fs.local

import io.github.linuskromnow.linusexplorer.fs.AccessDeniedFsException
import io.github.linuskromnow.linusexplorer.fs.AlreadyExistsFsException
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** The device's own file tree, rooted at "/". */
class LocalFileSystem : VirtualFileSystem {
    override val id = ID
    override val displayName = "This device"
    override val rootPath = VfsPaths.ROOT

    override suspend fun list(path: String): List<FileEntry> = io(path) {
        val dir = File(path)
        if (!dir.exists()) throw NotFoundFsException(path)
        if (!dir.isDirectory) throw FsException("“${dir.name}” is not a folder")
        val children = dir.listFiles() ?: throw AccessDeniedFsException(path)
        children.map { it.toEntry() }
    }

    override suspend fun stat(path: String): FileEntry = io(path) {
        val file = File(path)
        if (!file.exists()) throw NotFoundFsException(path)
        file.toEntry()
    }

    override suspend fun exists(path: String): Boolean = io(path) {
        Files.exists(Paths.get(path), LinkOption.NOFOLLOW_LINKS)
    }

    override suspend fun createDirectory(path: String) = io(path) {
        Files.createDirectory(Paths.get(path))
        Unit
    }

    override suspend fun createFile(path: String) = io(path) {
        Files.createFile(Paths.get(path))
        Unit
    }

    override suspend fun delete(path: String) = io(path) {
        val target = Paths.get(path)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw NotFoundFsException(path)
        deleteTree(target)
    }

    override suspend fun rename(path: String, newPath: String) = io(path) {
        Files.move(Paths.get(path), Paths.get(newPath))
        Unit
    }

    override suspend fun moveWithin(path: String, newPath: String): Boolean = io(path) {
        val target = Paths.get(newPath)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw AlreadyExistsFsException(newPath)
        try {
            Files.move(Paths.get(path), target, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: AtomicMoveNotSupportedException) {
            false // different mount point, e.g. internal storage -> SD card
        }
    }

    override suspend fun openInput(path: String): InputStream = io(path) { FileInputStream(path) }

    override suspend fun openOutput(path: String): OutputStream = io(path) { FileOutputStream(path) }

    private fun deleteTree(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(path).use { children -> children.forEach(::deleteTree) }
        }
        Files.delete(path)
    }

    private fun File.toEntry(): FileEntry {
        val directory = isDirectory
        return FileEntry(
            name = name,
            path = path,
            isDirectory = directory,
            size = if (directory) 0 else length(),
            lastModified = lastModified(),
            isSymlink = Files.isSymbolicLink(toPath()),
        )
    }

    /** Runs blocking I/O off the main thread and maps java.nio errors to [FsException]s. */
    private suspend fun <T> io(path: String, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: FsException) {
            throw e
        } catch (e: NoSuchFileException) {
            throw NotFoundFsException(e.file ?: path, e)
        } catch (e: FileAlreadyExistsException) {
            throw AlreadyExistsFsException(e.file ?: path, e)
        } catch (e: java.nio.file.AccessDeniedException) {
            throw AccessDeniedFsException(e.file ?: path, e)
        } catch (e: SecurityException) {
            throw AccessDeniedFsException(path, e)
        } catch (e: IOException) {
            throw FsException(e.message ?: "I/O error on ${VfsPaths.name(path)}", e)
        }
    }

    companion object {
        const val ID = "local"
    }
}
