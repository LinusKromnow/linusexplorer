package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.FsException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

enum class Protocol(val defaultPort: Int, val label: String) {
    SFTP(22, "SFTP"),
    FTP(21, "FTP"),

    /** Explicit TLS (AUTH TLS) on the normal FTP port. */
    FTPS(21, "FTPS"),
    ;

    /** Whether the server can be used without a user name (FTP's "anonymous"). */
    val allowsAnonymous: Boolean get() = this == FTP || this == FTPS
    val supportsKeys: Boolean get() = this == SFTP
}

/** A saved remote storage. Secrets live here too; the whole list is stored encrypted (see [ConnectionStore]). */
data class Connection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: Protocol,
    val host: String,
    val port: Int = protocol.defaultPort,
    val username: String = "",
    /** Null means "ask every time". */
    val password: String? = null,
    /** OpenSSH/PEM private key text, for key-based SFTP login. */
    val privateKey: String? = null,
    val keyPassphrase: String? = null,
    /** Folder to open after connecting. */
    val path: String = "/",
) {
    /** The [io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem] id this connection is registered under. */
    val fsId: String get() = fsIdFor(id)

    val label: String get() = name.ifBlank { if (username.isBlank()) host else "$username@$host" }

    companion object {
        private const val PREFIX = "net:"
        fun fsIdFor(connectionId: String) = PREFIX + connectionId
        fun connectionIdOf(fsId: String): String? = fsId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
    }
}

/** Binary format for the saved connections (kept free of Android APIs so it is unit-testable). */
internal object ConnectionCodec {
    private const val MAGIC = 0x4C45434E // "LECN"
    private const val VERSION = 1

    fun encode(connections: List<Connection>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(connections.size)
            for (c in connections) {
                out.writeUTF(c.id)
                out.writeUTF(c.name)
                out.writeUTF(c.protocol.name)
                out.writeUTF(c.host)
                out.writeInt(c.port)
                out.writeUTF(c.username)
                writeNullable(out, c.password)
                writeNullable(out, c.privateKey)
                writeNullable(out, c.keyPassphrase)
                out.writeUTF(c.path)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): List<Connection> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (input.readInt() != MAGIC) throw FsException("Saved connections are damaged")
        val version = input.readInt()
        if (version != VERSION) throw FsException("Saved connections need a newer LinusExplorer (format $version)")
        List(input.readInt()) {
            Connection(
                id = input.readUTF(),
                name = input.readUTF(),
                protocol = Protocol.valueOf(input.readUTF()),
                host = input.readUTF(),
                port = input.readInt(),
                username = input.readUTF(),
                password = readNullable(input),
                privateKey = readNullable(input),
                keyPassphrase = readNullable(input),
                path = input.readUTF(),
            )
        }
    }

    // writeUTF is limited to 64 KiB; private keys can be larger, so nullable strings use raw UTF-8 bytes.
    private fun writeNullable(out: DataOutputStream, value: String?) {
        if (value == null) {
            out.writeInt(-1)
        } else {
            val bytes = value.toByteArray()
            out.writeInt(bytes.size)
            out.write(bytes)
        }
    }

    private fun readNullable(input: DataInputStream): String? {
        val size = input.readInt()
        if (size < 0) return null
        return String(ByteArray(size).also { input.readFully(it) })
    }
}
