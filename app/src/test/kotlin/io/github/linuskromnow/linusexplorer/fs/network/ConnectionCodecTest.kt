package io.github.linuskromnow.linusexplorer.fs.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class ConnectionCodecTest {
    @Test
    fun roundTripWithShare() {
        val connection = Connection(id = "1", name = "NAS", protocol = Protocol.SMB, host = "nas.local", username = "HOME\\linus", password = "pw", share = "Photos")
        assertEquals(listOf(connection), ConnectionCodec.decode(ConnectionCodec.encode(listOf(connection))))
    }

    /** Connections saved before SMB existed (format 1, no share field) must keep loading. */
    @Test
    fun readsFormatOne() {
        val bytes = ByteArrayOutputStream().also { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(0x4C45434E)
                out.writeInt(1)
                out.writeInt(1)
                out.writeUTF("id-1")
                out.writeUTF("Old server")
                out.writeUTF("SFTP")
                out.writeUTF("example.org")
                out.writeInt(22)
                out.writeUTF("linus")
                out.writeInt(-1) // password: ask
                out.writeInt(-1) // no key
                out.writeInt(-1) // no passphrase
                out.writeUTF("/home/linus")
            }
        }.toByteArray()
        val expected = Connection(id = "id-1", name = "Old server", protocol = Protocol.SFTP, host = "example.org", port = 22, username = "linus", path = "/home/linus")
        assertEquals(listOf(expected), ConnectionCodec.decode(bytes))
    }
}
