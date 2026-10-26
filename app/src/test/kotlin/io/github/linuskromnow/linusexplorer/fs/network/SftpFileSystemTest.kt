package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.FileSystemRegistry
import io.github.linuskromnow.linusexplorer.fs.FileTransfer
import io.github.linuskromnow.linusexplorer.fs.InMemoryFileSystem
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.network.sftp.SftpFileSystem
import kotlinx.coroutines.test.runTest
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.Base64
import kotlin.random.Random

/** Talks to a real SFTP server (Apache MINA SSHD) started inside the test JVM. */
class SftpFileSystemTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var root: File
    private var acceptedKey: PublicKey? = null

    @Before
    fun startServer() {
        root = temp.newFolder("server-root")
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(temp.root, "hostkey.ser").toPath())
            passwordAuthenticator = PasswordAuthenticator { user, pass, _ -> user == "linus" && pass == "secret" }
            publickeyAuthenticator = PublickeyAuthenticator { user, key, _ -> user == "linus" && key == acceptedKey }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root.toPath())
            start()
        }
    }

    @After
    fun stopServer() = server.stop(true)

    private val knownHosts by lazy { KnownHosts(File(temp.root, "known_hosts")) }

    private fun connection(password: String? = "secret", key: String? = null) = Connection(
        id = "test",
        name = "test server",
        protocol = Protocol.SFTP,
        host = "127.0.0.1",
        port = server.port,
        username = "linus",
        password = password,
        privateKey = key,
    )

    /** Connects once to learn the host key and trusts it, like tapping "Trust" in the app. */
    private suspend fun trusted(fs: SftpFileSystem): SftpFileSystem {
        try {
            fs.list("/")
        } catch (e: UntrustedHostKeyException) {
            assertFalse(e.changed)
            assertTrue(e.fingerprint.startsWith("SHA256:"))
            knownHosts.trust(e.host, e.port, e.key)
        }
        return fs
    }

    @Test
    fun unknownHostKeyIsReportedThenTrusted() = runTest {
        val fs = SftpFileSystem(connection(), knownHosts)
        try {
            fs.list("/")
            fail("connected to an untrusted host")
        } catch (e: UntrustedHostKeyException) {
            knownHosts.trust(e.host, e.port, e.key)
        }
        assertEquals(emptyList<String>(), fs.list("/").map { it.name })
        fs.close()
    }

    @Test
    fun changedHostKeyIsRejected() = runTest {
        knownHosts.trust("127.0.0.1", server.port, byteArrayOf(1, 2, 3)) // pretend we saw another key before
        try {
            SftpFileSystem(connection(), knownHosts).list("/")
            fail("accepted a changed host key")
        } catch (e: UntrustedHostKeyException) {
            assertTrue(e.changed)
        }
    }

    @Test
    fun fileOperationsAndTransfers() = runTest {
        val sftp = trusted(SftpFileSystem(connection(), knownHosts))
        val phone = InMemoryFileSystem("phone").apply {
            write("/Photos/a.jpg", "jpeg bytes")
            write("/Photos/trip/b.txt", "notes")
        }
        val big = Random(1).nextBytes(3 * 1024 * 1024 + 17) // several SFTP packets, odd size
        phone.openOutput("/big.bin").use { it.write(big) }
        val registry = FileSystemRegistry().apply { register(phone); register(sftp) }
        val transfer = FileTransfer(registry)

        transfer.run(listOf(Location("phone", "/Photos"), Location("phone", "/big.bin")), Location(sftp.id, "/"), move = false) {}
        assertEquals("notes", File(root, "Photos/trip/b.txt").readText())
        assertArrayEquals(big, File(root, "big.bin").readBytes())
        assertEquals(listOf("Photos", "big.bin"), sftp.list("/").map { it.name }.sorted())
        assertTrue(sftp.stat("/Photos").isDirectory)
        assertEquals(big.size.toLong(), sftp.stat("/big.bin").size)

        // And back down again.
        val back = InMemoryFileSystem("back")
        registry.register(back)
        transfer.run(listOf(Location(sftp.id, "/big.bin")), Location("back", "/"), move = false) {}
        assertArrayEquals(big, back.openInput("/big.bin").readBytes())

        // Partial reads, as used to browse ZIPs on the server; the second one runs past the end.
        assertArrayEquals(big.copyOfRange(1_000_000, 1_065_536), sftp.readRange("/big.bin", 1_000_000, 65_536))
        assertArrayEquals(big.copyOfRange(big.size - 10, big.size), sftp.readRange("/big.bin", big.size - 10L, 100))

        sftp.createDirectory("/new")
        sftp.createFile("/new/empty.txt")
        sftp.rename("/new", "/renamed")
        assertTrue(sftp.exists("/renamed/empty.txt"))
        sftp.delete("/Photos")
        assertFalse(File(root, "Photos").exists())
        try {
            sftp.stat("/nope")
            fail("missing file found")
        } catch (e: NotFoundFsException) {
            // expected
        }
        sftp.close()
    }

    @Test
    fun missingAndWrongPasswordsAsk() = runTest {
        val fs = trusted(SftpFileSystem(connection(password = "wrong"), knownHosts))
        try {
            fs.list("/")
            fail("wrong password accepted")
        } catch (e: PasswordRequiredFsException) {
            assertTrue(e.wrongPassword)
        }
        val noPassword = SftpFileSystem(connection(password = null), knownHosts)
        try {
            noPassword.list("/")
            fail("connected without a password")
        } catch (e: PasswordRequiredFsException) {
            assertFalse(e.wrongPassword)
        }
        noPassword.setPassword("secret".toCharArray())
        noPassword.list("/")
        noPassword.close()
    }

    @Test
    fun privateKeyLogin() = runTest {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        acceptedKey = pair.public
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        val fs = trusted(SftpFileSystem(connection(password = null, key = pem), knownHosts))
        fs.createDirectory("/via-key")
        assertTrue(File(root, "via-key").isDirectory)
        fs.close()
    }

    @Test
    fun connectionsRoundTripEncrypted() {
        val box = object : SecretBox { // XOR stands in for the Android Keystore here
            override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
            override fun open(sealed: ByteArray) = seal(sealed)
        }
        val file = File(temp.root, "connections.bin")
        val store = ConnectionStore(file, box)
        val saved = connection().copy(privateKey = "x".repeat(100_000)) // longer than writeUTF allows
        store.save(saved)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("secret"))
        assertEquals(listOf(saved), ConnectionStore(file, box).connections.value)
        store.remove(saved.id)
        assertTrue(ConnectionStore(file, box).connections.value.isEmpty())
    }
}
