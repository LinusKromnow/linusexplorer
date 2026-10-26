package io.github.linuskromnow.linusexplorer.fs.network.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import io.github.linuskromnow.linusexplorer.fs.AccessDeniedFsException
import io.github.linuskromnow.linusexplorer.fs.AlreadyExistsFsException
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.PasswordProtected
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.RangeReadable
import io.github.linuskromnow.linusexplorer.fs.readFully
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** One SMB 2/3 share (Windows, Samba, NAS). "/" is the share's root. */
class SmbFileSystem(private val connection: Connection) : VirtualFileSystem, PasswordProtected, RangeReadable, Closeable {
    override val id = connection.fsId
    override val displayName = connection.label
    override val rootPath = VfsPaths.ROOT

    private val guest = connection.username.isBlank()
    private val mutex = Mutex()
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .withSoTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build(),
    )
    private var session: Session? = null
    private var share: DiskShare? = null

    @Volatile private var password: CharArray? = connection.password?.toCharArray()

    override fun setPassword(password: CharArray) {
        this.password = password
    }

    override suspend fun list(path: String): List<FileEntry> = call(path, retry = true) { share ->
        val dir = VfsPaths.normalize(path)
        share.list(smbPath(dir))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val directory = EnumWithValue.EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                FileEntry(
                    name = info.fileName,
                    path = VfsPaths.child(dir, info.fileName),
                    isDirectory = directory,
                    size = if (directory) 0 else info.endOfFile,
                    lastModified = info.lastWriteTime.toEpochMillis(),
                    isHidden = info.fileName.startsWith(".") ||
                        EnumWithValue.EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_HIDDEN),
                )
            }
    }

    override suspend fun stat(path: String): FileEntry = call(path, retry = true) { share -> statWith(share, VfsPaths.normalize(path)) }

    override suspend fun exists(path: String): Boolean = call(path, retry = true) { share ->
        val p = smbPath(path)
        p.isEmpty() || share.fileExists(p) || share.folderExists(p)
    }

    override suspend fun createDirectory(path: String) = call(path) { share ->
        val p = smbPath(path)
        if (share.fileExists(p) || share.folderExists(p)) throw AlreadyExistsFsException(path)
        share.mkdir(p)
    }

    override suspend fun createFile(path: String) = call(path) { share ->
        share.openFile(
            smbPath(path), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_CREATE, null,
        ).close()
    }

    override suspend fun delete(path: String) = call(path) { share ->
        val p = smbPath(path)
        if (share.folderExists(p)) share.rmdir(p, true) else share.rm(p)
    }

    override suspend fun rename(path: String, newPath: String) = call(path) { share ->
        val target = smbPath(newPath)
        if (share.fileExists(target) || share.folderExists(target)) throw AlreadyExistsFsException(newPath)
        share.open(
            smbPath(path), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null,
        ).use { it.rename(target, false) }
    }

    override suspend fun moveWithin(path: String, newPath: String): Boolean {
        rename(path, newPath)
        return true
    }

    override suspend fun openInput(path: String): InputStream = call(path, retry = true) { share ->
        val file = share.openFile(
            smbPath(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null,
        )
        object : FilterInputStream(file.inputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray = call(path, retry = true) { share ->
        share.openFile(
            smbPath(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null,
        ).use { file -> readFully(length) { buffer, done, wanted -> file.read(buffer, offset + done, done, wanted) } }
    }

    override suspend fun openOutput(path: String): OutputStream = call(path) { share ->
        val file = share.openFile(
            smbPath(path), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
        )
        object : FilterOutputStream(file.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

            override fun close() {
                try {
                    super.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    override fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        share = null
        session = null
    }

    // region internals

    private fun statWith(share: DiskShare, path: String): FileEntry {
        if (path == VfsPaths.ROOT) return FileEntry(VfsPaths.ROOT, VfsPaths.ROOT, isDirectory = true, size = 0, lastModified = 0)
        val info = share.getFileInformation(smbPath(path))
        val directory = info.standardInformation.isDirectory
        return FileEntry(
            name = VfsPaths.name(path),
            path = path,
            isDirectory = directory,
            size = if (directory) 0 else info.standardInformation.endOfFile,
            lastModified = info.basicInformation.lastWriteTime.toEpochMillis(),
        )
    }

    /** "/a/b" -> "a\b", relative to the share root. */
    private fun smbPath(path: String) = VfsPaths.normalize(path).removePrefix("/").replace('/', '\\')

    private suspend fun <T> call(path: String, retry: Boolean = false, block: (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        try {
            mapErrors(path) { block(share()) }
        } catch (e: ConnectionLostException) {
            if (!retry) throw FsException("Connection to ${connection.host} lost", e)
            mapErrors(path) { block(share()) }
        }
    }

    private inline fun <T> mapErrors(path: String, block: () -> T): T = try {
        block()
    } catch (e: FsException) {
        throw e
    } catch (e: SMBApiException) {
        throw when (e.status) {
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NO_SUCH_FILE ->
                NotFoundFsException(path, e)
            NtStatus.STATUS_ACCESS_DENIED -> AccessDeniedFsException(path, e)
            NtStatus.STATUS_OBJECT_NAME_COLLISION -> AlreadyExistsFsException(path, e)
            NtStatus.STATUS_LOGON_FAILURE -> PasswordRequiredFsException(id, wrongPassword = true, e)
            NtStatus.STATUS_BAD_NETWORK_NAME -> FsException("The share “${connection.share}” does not exist on ${connection.host}", e)
            else -> FsException(e.message ?: "SMB error on ${VfsPaths.name(path)}", e)
        }
    } catch (e: IOException) {
        close()
        throw ConnectionLostException(e)
    }

    private class ConnectionLostException(cause: IOException) : IOException(cause)

    private suspend fun share(): DiskShare = mutex.withLock {
        share?.takeIf { it.isConnected }?.let { return it }
        close()
        val auth = if (guest) {
            AuthenticationContext.guest()
        } else {
            val secret = password ?: throw PasswordRequiredFsException(id, wrongPassword = false)
            // "DOMAIN\user" logs in to a domain; a plain name uses the server's local accounts.
            val (domain, user) = connection.username.split('\\', limit = 2).let { if (it.size == 2) it[0] to it[1] else null to it[0] }
            AuthenticationContext(user, secret.copyOf(), domain)
        }
        val newSession = try {
            client.connect(connection.host, connection.port).authenticate(auth)
        } catch (e: SMBApiException) {
            if (e.status == NtStatus.STATUS_LOGON_FAILURE && !guest) {
                password = null
                throw PasswordRequiredFsException(id, wrongPassword = true, e)
            }
            throw FsException("Cannot log in to ${connection.host}: ${e.status}", e)
        } catch (e: IOException) {
            throw FsException("Cannot connect to ${connection.host}: ${e.message}", e)
        }
        val disk = newSession.connectShare(connection.share) as? DiskShare
            ?: throw FsException("“${connection.share}” is not a file share")
        session = newSession
        disk.also { share = it }
    }

    private companion object {
        const val READ_TIMEOUT_S = 30L
    }

    // endregion
}
