package io.github.linuskromnow.linusexplorer.fs.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Encrypts small blobs at rest; on Android backed by a hardware Keystore key. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * Saved connections, stored as one encrypted file so host names, user names and passwords are
 * never readable on disk.
 */
class ConnectionStore(private val file: File, private val box: SecretBox) {
    private val _connections = MutableStateFlow(load())
    val connections: StateFlow<List<Connection>> = _connections.asStateFlow()

    operator fun get(id: String): Connection? = _connections.value.find { it.id == id }

    @Synchronized
    fun save(connection: Connection) {
        val list = _connections.value
        val updated = if (list.any { it.id == connection.id }) list.map { if (it.id == connection.id) connection else it } else list + connection
        write(updated)
    }

    @Synchronized
    fun remove(id: String) = write(_connections.value.filterNot { it.id == id })

    private fun write(list: List<Connection>) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeBytes(box.seal(ConnectionCodec.encode(list)))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        _connections.value = list
    }

    /**
     * An unreadable file (e.g. restored onto a new phone, whose Keystore lacks the key) is set
     * aside instead of crashing the app; the user re-adds the connections.
     */
    private fun load(): List<Connection> {
        if (!file.exists()) return emptyList()
        return try {
            ConnectionCodec.decode(box.open(file.readBytes()))
        } catch (e: Exception) {
            file.renameTo(File(file.path + ".unreadable"))
            emptyList()
        }
    }
}
