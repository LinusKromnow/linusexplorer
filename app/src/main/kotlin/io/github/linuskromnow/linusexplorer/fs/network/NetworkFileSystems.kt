package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.FileSystemRegistry
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.dropbox.DropboxFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.ftp.FtpFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.onedrive.OneDriveFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.s3.S3FileSystem
import io.github.linuskromnow.linusexplorer.fs.network.sftp.SftpFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.smb.SmbFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.webdav.WebDavFileSystem
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File

/** Creates, registers and tears down the file systems behind saved [Connection]s. */
class NetworkFileSystems(
    private val registry: FileSystemRegistry,
    val store: ConnectionStore,
    val knownHosts: KnownHosts,
    /** Where cloud uploads that need their size up front (OneDrive) are staged. */
    private val spoolDir: File,
) {
    /** Shared so all HTTP-based connections (WebDAV, S3, clouds) reuse one connection pool and thread pool. */
    val httpClient by lazy { OkHttpClient() }

    /** Registers (or returns the already registered) file system for [connection]. */
    fun open(connection: Connection): VirtualFileSystem =
        registry.find(connection.fsId) ?: create(connection).also { registry.register(it) }

    /** Resolver for [FileSystemRegistry]: brings saved connections back after an app restart. */
    fun resolve(fsId: String): VirtualFileSystem? =
        Connection.connectionIdOf(fsId)?.let { store[it] }?.let(::create)

    fun save(connection: Connection) {
        store.save(connection)
        disconnect(connection.fsId) // reconnect with the new settings next time
    }

    fun remove(connection: Connection) {
        store.remove(connection.id)
        disconnect(connection.fsId)
    }

    private fun disconnect(fsId: String) {
        (registry.find(fsId) as? Closeable)?.let { fs -> Thread { runCatching { fs.close() } }.start() }
        registry.unregister(fsId)
    }

    private fun create(connection: Connection): VirtualFileSystem = when (connection.protocol) {
        Protocol.SFTP -> SftpFileSystem(connection, knownHosts)
        Protocol.FTP, Protocol.FTPS -> FtpFileSystem(connection, knownHosts)
        Protocol.SMB -> SmbFileSystem(connection)
        Protocol.WEBDAV, Protocol.WEBDAV_HTTP -> WebDavFileSystem(connection, knownHosts, httpClient)
        Protocol.S3, Protocol.S3_HTTP -> S3FileSystem(connection, knownHosts, httpClient)
        Protocol.DROPBOX -> DropboxFileSystem(connection, httpClient)
        Protocol.ONEDRIVE -> OneDriveFileSystem(connection, spoolDir, httpClient, onRefreshTokenChanged = { token ->
            // Microsoft rotates refresh tokens; the old one stops working, so store the new one now.
            store[connection.id]?.let { store.save(it.copy(password = token)) }
        })
    }
}
