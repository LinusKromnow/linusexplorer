package io.github.linuskromnow.linusexplorer.fs.network.sftp

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
import io.github.linuskromnow.linusexplorer.fs.network.KnownHosts
import io.github.linuskromnow.linusexplorer.fs.network.UntrustedHostKeyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.util.EnumSet

/** A remote folder tree over SSH (SFTP). Connects lazily and reconnects once if the connection dropped. */
class SftpFileSystem(
    private val connection: Connection,
    private val knownHosts: KnownHosts,
) : VirtualFileSystem, PasswordProtected, RangeReadable, Closeable {
    override val id = connection.fsId
    override val displayName = connection.label
    override val rootPath = VfsPaths.ROOT

    private val mutex = Mutex()
    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null

    @Volatile private var password: CharArray? = connection.password?.toCharArray()

    override fun setPassword(password: CharArray) {
        this.password = password
    }

    override suspend fun list(path: String): List<FileEntry> = call(path, retry = true) { client ->
        val dir = VfsPaths.normalize(path)
        client.ls(dir).map { info ->
            val childPath = VfsPaths.child(dir, info.name)
            val attrs = info.attributes
            val symlink = attrs.type == FileMode.Type.SYMLINK
            // Follow links to decide whether they lead to a folder; broken links show as files.
            val target = if (symlink) runCatching { client.stat(childPath) }.getOrNull() ?: attrs else attrs
            entry(childPath, target, symlink)
        }
    }

    override suspend fun stat(path: String): FileEntry = call(path, retry = true) { client ->
        val p = VfsPaths.normalize(path)
        val attrs = client.lstat(p)
        if (attrs.type == FileMode.Type.SYMLINK) entry(p, client.stat(p), symlink = true) else entry(p, attrs, symlink = false)
    }

    override suspend fun exists(path: String): Boolean = call(path, retry = true) { client ->
        client.statExistence(VfsPaths.normalize(path)) != null
    }

    override suspend fun createDirectory(path: String) = call(path) { client ->
        if (client.statExistence(path) != null) throw AlreadyExistsFsException(path)
        client.mkdir(path)
    }

    override suspend fun createFile(path: String) = call(path) { client ->
        client.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).close()
    }

    override suspend fun delete(path: String) = call(path) { client -> deleteTree(client, VfsPaths.normalize(path)) }

    override suspend fun rename(path: String, newPath: String) = call(path) { client ->
        if (client.statExistence(newPath) != null) throw AlreadyExistsFsException(newPath)
        client.rename(path, newPath)
    }

    override suspend fun moveWithin(path: String, newPath: String): Boolean {
        rename(path, newPath)
        return true
    }

    override suspend fun openInput(path: String): InputStream = call(path, retry = true) { client ->
        val file = client.open(path, EnumSet.of(OpenMode.READ))
        object : FilterInputStream(file.ReadAheadRemoteFileInputStream(READ_AHEAD)) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray = call(path, retry = true) { client ->
        client.open(path, EnumSet.of(OpenMode.READ)).use { file ->
            readFully(length) { buffer, done, wanted -> file.read(offset + done, buffer, done, wanted) }
        }
    }

    override suspend fun openOutput(path: String): OutputStream = call(path) { client ->
        val file: RemoteFile = client.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
        object : FilterOutputStream(file.RemoteFileOutputStream(0, UNCONFIRMED_WRITES)) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len) // skip the byte-by-byte default

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
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        sftp = null
        ssh = null
    }

    // region internals

    private fun deleteTree(client: SFTPClient, path: String) {
        val attrs = client.lstat(path)
        if (attrs.type == FileMode.Type.DIRECTORY) {
            for (child in client.ls(path)) deleteTree(client, VfsPaths.child(path, child.name))
            client.rmdir(path)
        } else {
            client.rm(path)
        }
    }

    private fun entry(path: String, attrs: FileAttributes, symlink: Boolean): FileEntry {
        val directory = attrs.type == FileMode.Type.DIRECTORY
        return FileEntry(
            name = VfsPaths.name(path),
            path = path,
            isDirectory = directory,
            size = if (directory) 0 else attrs.size,
            lastModified = attrs.mtime * 1000,
            isSymlink = symlink,
        )
    }

    /**
     * Runs [block] with a connected client on the IO dispatcher and maps sshj errors to
     * [FsException]s. Read-only calls ([retry]) reconnect once when the connection was lost.
     */
    private suspend fun <T> call(path: String, retry: Boolean = false, block: (SFTPClient) -> T): T = withContext(Dispatchers.IO) {
        try {
            mapErrors(path) { block(client()) }
        } catch (e: ConnectionLostException) {
            if (!retry) throw FsException("Connection to ${connection.host} lost", e)
            mapErrors(path) { block(client()) }
        }
    }

    private inline fun <T> mapErrors(path: String, block: () -> T): T = try {
        block()
    } catch (e: FsException) {
        throw e
    } catch (e: SFTPException) {
        throw when (e.statusCode) {
            Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH -> NotFoundFsException(path, e)
            Response.StatusCode.PERMISSION_DENIED -> AccessDeniedFsException(path, e)
            Response.StatusCode.FILE_ALREADY_EXISTS -> AlreadyExistsFsException(path, e)
            else -> FsException(e.message ?: "SFTP error on ${VfsPaths.name(path)}", e)
        }
    } catch (e: IOException) {
        close() // the session is probably dead; the next call reconnects
        throw ConnectionLostException(e)
    }

    private class ConnectionLostException(cause: IOException) : IOException(cause)

    private suspend fun client(): SFTPClient = mutex.withLock {
        val current = sftp
        if (current != null && ssh?.isConnected == true && ssh?.isAuthenticated == true) return current
        close()
        connect()
    }

    private fun connect(): SFTPClient {
        val verifier = TofuVerifier()
        val client = SSHClient(DefaultConfig())
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.timeout = READ_TIMEOUT_MS
        client.addHostKeyVerifier(verifier)
        try {
            client.connect(connection.host, connection.port)
        } catch (e: TransportException) {
            client.disconnectQuietly()
            verifier.rejected?.let { (key, changed) ->
                throw UntrustedHostKeyException(id, connection.host, connection.port, key, changed)
            }
            throw FsException("Cannot connect to ${connection.host}: ${e.message}", e)
        } catch (e: IOException) {
            client.disconnectQuietly()
            throw FsException("Cannot connect to ${connection.host}: ${e.message}", e)
        }
        try {
            val privateKey = connection.privateKey
            if (privateKey != null) {
                val passphrase = connection.keyPassphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                client.authPublickey(connection.username, client.loadKeys(privateKey, null, passphrase))
            } else {
                val secret = password ?: throw PasswordRequiredFsException(id, wrongPassword = false)
                client.authPassword(connection.username, secret.copyOf())
            }
        } catch (e: UserAuthException) {
            client.disconnectQuietly()
            if (connection.privateKey != null) throw FsException("The server did not accept the key for ${connection.username}", e)
            password = null
            throw PasswordRequiredFsException(id, wrongPassword = true, e)
        } catch (e: Exception) {
            client.disconnectQuietly()
            throw e
        }
        ssh = client
        return client.newSFTPClient().also { sftp = it }
    }

    private fun SSHClient.disconnectQuietly() = runCatching { disconnect() }

    /** Checks the server key against [knownHosts] and remembers what it rejected, for the trust prompt. */
    private inner class TofuVerifier : HostKeyVerifier {
        var rejected: Pair<ByteArray, Boolean>? = null

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            return when (knownHosts.check(connection.host, connection.port, blob)) {
                KnownHosts.Verdict.TRUSTED -> true
                KnownHosts.Verdict.UNKNOWN -> false.also { rejected = blob to false }
                KnownHosts.Verdict.CHANGED -> false.also { rejected = blob to true }
            }
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val READ_AHEAD = 16
        private const val UNCONFIRMED_WRITES = 16
    }

    // endregion
}
