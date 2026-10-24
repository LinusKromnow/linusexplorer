package io.github.linuskromnow.linusexplorer.fs.network.ftp

import android.annotation.SuppressLint
import io.github.linuskromnow.linusexplorer.fs.AlreadyExistsFsException
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.PasswordProtected
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.Connection
import io.github.linuskromnow.linusexplorer.fs.network.KnownHosts
import io.github.linuskromnow.linusexplorer.fs.network.Protocol
import io.github.linuskromnow.linusexplorer.fs.network.UntrustedHostKeyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.X509TrustManager

/**
 * FTP and explicit FTPS (AUTH TLS). One control connection serves listings and file operations;
 * every open stream gets its own connection, since FTP allows one transfer per connection.
 */
class FtpFileSystem(
    private val connection: Connection,
    private val knownHosts: KnownHosts,
) : VirtualFileSystem, PasswordProtected, Closeable {
    override val id = connection.fsId
    override val displayName = connection.label
    override val rootPath = VfsPaths.ROOT

    private val tls = connection.protocol == Protocol.FTPS
    private val anonymous = connection.username.isBlank()
    private val mutex = Mutex()
    private var control: FTPClient? = null

    @Volatile private var password: CharArray? = connection.password?.toCharArray()

    override fun setPassword(password: CharArray) {
        this.password = password
    }

    override suspend fun list(path: String): List<FileEntry> = control(path, retry = true) { client ->
        val dir = VfsPaths.normalize(path)
        val files = if (client.hasFeature("MLSD")) client.mlistDir(dir) else client.listFiles(dir)
        if (!FTPReply.isPositiveCompletion(client.replyCode)) throw replyError(client, dir)
        files.filterNotNull()
            .filter { it.name != "." && it.name != ".." && !it.name.contains('/') }
            .map { entry(VfsPaths.child(dir, it.name), it) }
    }

    override suspend fun stat(path: String): FileEntry = control(path, retry = true) { client -> statWith(client, VfsPaths.normalize(path)) }

    override suspend fun exists(path: String): Boolean = control(path, retry = true) { client ->
        try {
            statWith(client, VfsPaths.normalize(path))
            true
        } catch (e: NotFoundFsException) {
            false
        }
    }

    override suspend fun createDirectory(path: String) = control(path) { client ->
        if (existsWith(client, path)) throw AlreadyExistsFsException(path)
        if (!client.makeDirectory(path)) throw replyError(client, path)
    }

    override suspend fun createFile(path: String) = control(path) { client ->
        if (existsWith(client, path)) throw AlreadyExistsFsException(path)
        if (!client.storeFile(path, ByteArrayInputStream(ByteArray(0)))) throw replyError(client, path)
    }

    override suspend fun delete(path: String) = control(path) { client -> deleteTree(client, statWith(client, VfsPaths.normalize(path))) }

    override suspend fun rename(path: String, newPath: String) = control(path) { client ->
        if (existsWith(client, newPath)) throw AlreadyExistsFsException(newPath)
        if (!client.rename(path, newPath)) throw replyError(client, path)
    }

    override suspend fun moveWithin(path: String, newPath: String): Boolean {
        rename(path, newPath)
        return true
    }

    override suspend fun openInput(path: String): InputStream = withContext(Dispatchers.IO) {
        val client = connect()
        val stream = try {
            client.retrieveFileStream(path) ?: throw replyError(client, path)
        } catch (t: Throwable) {
            client.closeQuietly()
            throw t
        }
        object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                    client.completePendingCommand()
                } finally {
                    client.closeQuietly()
                }
            }
        }
    }

    override suspend fun openOutput(path: String): OutputStream = withContext(Dispatchers.IO) {
        val client = connect()
        val stream = try {
            client.storeFileStream(path) ?: throw replyError(client, path)
        } catch (t: Throwable) {
            client.closeQuietly()
            throw t
        }
        object : FilterOutputStream(stream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

            override fun close() {
                try {
                    super.close()
                    if (!client.completePendingCommand()) throw replyError(client, path)
                } finally {
                    client.closeQuietly()
                }
            }
        }
    }

    override fun close() {
        control?.closeQuietly()
        control = null
    }

    // region internals

    private fun statWith(client: FTPClient, path: String): FileEntry {
        if (path == VfsPaths.ROOT) return FileEntry(VfsPaths.ROOT, VfsPaths.ROOT, isDirectory = true, size = 0, lastModified = 0)
        if (client.hasFeature("MLST")) {
            val file = client.mlistFile(path)
            if (file != null) return entry(path, file)
            // Servers answer MLST for a missing path with 550, 450 or (Apache FtpServer) 501.
            if (client.replyCode in NOT_FOUND_REPLIES || FTPReply.isPositiveCompletion(client.replyCode)) throw NotFoundFsException(path)
            throw replyError(client, path)
        }
        // Without MLST, find the entry in its parent's listing.
        val parent = VfsPaths.parent(path)!!
        val name = VfsPaths.name(path)
        val file = client.listFiles(parent).firstOrNull { it?.name == name } ?: throw NotFoundFsException(path)
        return entry(path, file)
    }

    private fun existsWith(client: FTPClient, path: String) = try {
        statWith(client, VfsPaths.normalize(path))
        true
    } catch (e: NotFoundFsException) {
        false
    }

    private fun deleteTree(client: FTPClient, entry: FileEntry) {
        if (entry.isDirectory && !entry.isSymlink) {
            val children = if (client.hasFeature("MLSD")) client.mlistDir(entry.path) else client.listFiles(entry.path)
            for (child in children.filterNotNull()) {
                if (child.name == "." || child.name == "..") continue
                deleteTree(client, entry(VfsPaths.child(entry.path, child.name), child))
            }
            if (!client.removeDirectory(entry.path)) throw replyError(client, entry.path)
        } else if (!client.deleteFile(entry.path)) {
            throw replyError(client, entry.path)
        }
    }

    private fun entry(path: String, file: FTPFile) = FileEntry(
        name = VfsPaths.name(path),
        path = path,
        isDirectory = file.isDirectory,
        size = if (file.isDirectory) 0 else file.size.coerceAtLeast(0),
        lastModified = file.timestamp?.timeInMillis ?: 0,
        isSymlink = file.isSymbolicLink,
    )

    private fun replyError(client: FTPClient, path: String): FsException {
        val reply = client.replyString?.trim().orEmpty()
        return when (client.replyCode) {
            FTPReply.FILE_UNAVAILABLE -> if (reply.contains("permission", ignoreCase = true)) {
                FsException("Permission denied: ${VfsPaths.name(path)}")
            } else {
                NotFoundFsException(path)
            }
            FTPReply.NOT_LOGGED_IN -> PasswordRequiredFsException(id, wrongPassword = true)
            else -> FsException(reply.ifEmpty { "FTP error on ${VfsPaths.name(path)}" })
        }
    }

    /** Runs [block] on the shared control connection; read-only calls ([retry]) reconnect once if it dropped. */
    private suspend fun <T> control(path: String, retry: Boolean = false, block: (FTPClient) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                block(control ?: connect().also { control = it })
            } catch (e: FsException) {
                throw e
            } catch (e: IOException) {
                close()
                if (!retry) throw FsException("Connection to ${connection.host} lost", e)
                try {
                    block(connect().also { control = it })
                } catch (again: IOException) {
                    close()
                    throw FsException("Connection to ${connection.host} lost", again)
                }
            }
        }
    }

    /** Opens and logs in a new connection. */
    private fun connect(): FTPClient {
        val trust = TofuTrustManager()
        val client = if (tls) FTPSClient("TLS", false).apply { trustManager = trust } else FTPClient()
        client.controlEncoding = "UTF-8"
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.setDataTimeout(Duration.ofMillis(READ_TIMEOUT_MS.toLong()))
        try {
            client.connect(connection.host, connection.port)
            client.soTimeout = READ_TIMEOUT_MS
            if (!FTPReply.isPositiveCompletion(client.replyCode)) throw FsException("The server refused the connection: ${client.replyString?.trim()}")
        } catch (e: IOException) {
            client.closeQuietly()
            trust.rejected?.let { (cert, changed) -> throw UntrustedHostKeyException(id, connection.host, connection.port, cert, changed) }
            if (e is FsException) throw e
            throw FsException("Cannot connect to ${connection.host}: ${e.message}", e)
        }
        val user = if (anonymous) "anonymous" else connection.username
        val secret = if (anonymous) "" else String(password ?: run { client.closeQuietly(); throw PasswordRequiredFsException(id, wrongPassword = false) })
        if (!client.login(user, secret)) {
            client.closeQuietly()
            if (!anonymous) password = null
            throw PasswordRequiredFsException(id, wrongPassword = true)
        }
        if (client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P") // encrypt file data too, not only the control channel
        }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        return client
    }

    private fun FTPClient.closeQuietly() {
        runCatching { if (isConnected) logout() }
        runCatching { if (isConnected) disconnect() }
    }

    /**
     * Trust-on-first-use for the server's TLS certificate, like SSH host keys. Deliberately not the
     * platform trust manager: home FTPS servers almost always use self-signed certificates, and
     * pinning the exact certificate the user approved is stricter than CA validation.
     */
    @SuppressLint("CustomX509TrustManager")
    private inner class TofuTrustManager : X509TrustManager {
        var rejected: Pair<ByteArray, Boolean>? = null

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val cert = chain.firstOrNull()?.encoded ?: throw CertificateException("No server certificate")
            when (knownHosts.check(connection.host, connection.port, cert)) {
                KnownHosts.Verdict.TRUSTED -> Unit
                KnownHosts.Verdict.UNKNOWN -> {
                    rejected = cert to false
                    throw CertificateException("Untrusted server certificate")
                }
                KnownHosts.Verdict.CHANGED -> {
                    rejected = cert to true
                    throw CertificateException("Server certificate changed")
                }
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("Client certificates are not used")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        val NOT_FOUND_REPLIES = setOf(FTPReply.FILE_UNAVAILABLE, FTPReply.FILE_ACTION_NOT_TAKEN, FTPReply.SYNTAX_ERROR_IN_ARGUMENTS)
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }

    // endregion
}
