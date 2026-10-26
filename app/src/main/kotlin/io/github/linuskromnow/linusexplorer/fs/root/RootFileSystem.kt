package io.github.linuskromnow.linusexplorer.fs.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import io.github.linuskromnow.linusexplorer.fs.AccessDeniedFsException
import io.github.linuskromnow.linusexplorer.fs.AlreadyExistsFsException
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume

/**
 * Runs in a separate process with root rights (started by libsu through `su`) and serves file
 * access to the app over Binder.
 */
class RootFileService : RootService() {
    override fun onBind(intent: Intent): IBinder = FileSystemManager.getService()
}

/**
 * The whole device file tree with superuser rights, for rooted phones. Nothing touches `su` until
 * the first use, which is when the root manager (e.g. Magisk) asks the user.
 */
class RootFileSystem(context: Context) : VirtualFileSystem {
    private val context = context.applicationContext
    private val mutex = Mutex()
    @Volatile private var remote: FileSystemManager? = null

    override val id = ID
    override val displayName = "Root (superuser)"
    override val rootPath = VfsPaths.ROOT

    override suspend fun list(path: String): List<FileEntry> = io(path) { fs ->
        val dir = fs.getFile(path)
        if (!dir.exists()) throw NotFoundFsException(path)
        if (!dir.isDirectory) throw FsException("“${dir.name}” is not a folder")
        (dir.listFiles() ?: throw AccessDeniedFsException(path)).map { it.toEntry() }
    }

    override suspend fun stat(path: String): FileEntry = io(path) { fs ->
        val file = fs.getFile(path)
        if (!file.exists() && !file.isSymlink) throw NotFoundFsException(path)
        file.toEntry()
    }

    override suspend fun exists(path: String): Boolean = io(path) { fs ->
        fs.getFile(path).let { it.exists() || it.isSymlink }
    }

    override suspend fun createDirectory(path: String) = io(path) { fs ->
        val dir = fs.getFile(path)
        if (dir.exists()) throw AlreadyExistsFsException(path)
        if (!dir.mkdir()) throw FsException("Can't create “${dir.name}”")
    }

    override suspend fun createFile(path: String) = io(path) { fs ->
        if (!fs.getFile(path).createNewFile()) throw AlreadyExistsFsException(path)
    }

    override suspend fun delete(path: String) = io(path) { fs ->
        val file = fs.getFile(path)
        if (!file.exists() && !file.isSymlink) throw NotFoundFsException(path)
        deleteTree(file)
    }

    override suspend fun rename(path: String, newPath: String) = io(path) { fs ->
        val target = fs.getFile(newPath)
        if (target.exists() || target.isSymlink) throw AlreadyExistsFsException(newPath)
        if (!fs.getFile(path).renameTo(target)) throw FsException("Can't rename “${VfsPaths.name(path)}”")
    }

    /** rename(2) fails across mount points; [io.github.linuskromnow.linusexplorer.fs.FileTransfer] then copies. */
    override suspend fun moveWithin(path: String, newPath: String): Boolean = io(path) { fs ->
        val target = fs.getFile(newPath)
        if (target.exists() || target.isSymlink) throw AlreadyExistsFsException(newPath)
        fs.getFile(path).renameTo(target)
    }

    override suspend fun openInput(path: String): InputStream = io(path) { fs -> fs.getFile(path).newInputStream() }

    override suspend fun openOutput(path: String): OutputStream = io(path) { fs -> fs.getFile(path).newOutputStream() }

    /** Symbolic links are removed, never followed. */
    private fun deleteTree(file: ExtendedFile) {
        if (file.isDirectory && !file.isSymlink) file.listFiles()?.forEach(::deleteTree)
        if (!file.delete()) throw FsException("Can't delete “${file.name}”")
    }

    private fun ExtendedFile.toEntry(): FileEntry {
        val directory = isDirectory
        return FileEntry(
            name = name,
            path = path,
            isDirectory = directory,
            size = if (directory) 0 else length(),
            lastModified = lastModified(),
            isSymlink = isSymlink,
        )
    }

    /** Connects on first use; asking for root may show the root manager's prompt. */
    private suspend fun connect(): FileSystemManager = mutex.withLock {
        remote?.let { return it }
        val granted = withContext(Dispatchers.IO) { Shell.getShell().isRoot }
        if (!granted) throw FsException("Root access was denied. Allow LinusExplorer in your root manager app.")
        val binder = withContext(Dispatchers.Main) { bindService() }
        FileSystemManager.getRemote(binder).also { remote = it }
    }

    private suspend fun bindService(): IBinder = suspendCancellableCoroutine { continuation ->
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (continuation.isActive) continuation.resume(service)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                remote = null // the root process died; reconnect next time
            }
        }
        RootService.bind(Intent(context, RootFileService::class.java), connection)
    }

    private suspend fun <T> io(path: String, block: (FileSystemManager) -> T): T {
        val fs = connect()
        return withContext(Dispatchers.IO) {
            try {
                block(fs)
            } catch (e: FsException) {
                throw e
            } catch (e: IOException) {
                throw FsException(e.message ?: "I/O error on ${VfsPaths.name(path)}", e)
            } catch (e: RuntimeException) {
                // Binder failures surface as runtime exceptions when the root process went away.
                remote = null
                throw FsException(e.message ?: "Root service error", e)
            }
        }
    }

    companion object {
        const val ID = "root"
    }
}
