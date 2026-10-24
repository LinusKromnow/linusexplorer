package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.FileSystemRegistry
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.sftp.SftpFileSystem
import java.io.Closeable

/** Creates, registers and tears down the file systems behind saved [Connection]s. */
class NetworkFileSystems(
    private val registry: FileSystemRegistry,
    val store: ConnectionStore,
    val knownHosts: KnownHosts,
) {
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
    }
}
