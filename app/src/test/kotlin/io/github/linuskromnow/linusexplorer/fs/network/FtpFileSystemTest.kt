package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.FileSystemRegistry
import io.github.linuskromnow.linusexplorer.fs.FileTransfer
import io.github.linuskromnow.linusexplorer.fs.InMemoryFileSystem
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.network.ftp.FtpFileSystem
import kotlinx.coroutines.test.runTest
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfigurationFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import kotlin.random.Random

/** Talks to a real FTP/FTPS server (Apache FtpServer) started inside the test JVM. */
class FtpFileSystemTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var server: FtpServer? = null
    private lateinit var root: File
    private var port = 0
    private val knownHosts by lazy { KnownHosts(File(temp.root, "known_hosts")) }

    @After
    fun stop() {
        server?.stop()
    }

    private fun start(tls: Boolean, anonymous: Boolean = false) {
        root = temp.newFolder("ftp-root")
        port = ServerSocket(0).use { it.localPort }
        val listener = ListenerFactory().apply {
            port = this@FtpFileSystemTest.port
            serverAddress = "127.0.0.1"
            if (tls) {
                sslConfiguration = SslConfigurationFactory().apply {
                    keystoreFile = selfSignedKeystore()
                    keystorePassword = "pw"
                    keyPassword = "pw"
                    keystoreType = "PKCS12"
                }.createSslConfiguration()
                isImplicitSsl = false
            }
        }
        val usersFile = File(temp.root, "users.properties").apply { createNewFile() }
        val users = PropertiesUserManagerFactory().apply { file = usersFile }.createUserManager()
        users.save(BaseUser().apply { name = "linus"; password = "secret"; homeDirectory = root.path; authorities = listOf(WritePermission()) })
        if (anonymous) users.save(BaseUser().apply { name = "anonymous"; homeDirectory = root.path })
        server = FtpServerFactory().apply {
            addListener("default", listener.createListener())
            userManager = users
        }.createServer().also { it.start() }
    }

    private fun selfSignedKeystore(): File {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val name = X500Name("CN=localhost")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(now - 86_400_000), Date(now + 86_400_000), name, pair.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        val file = File(temp.root, "server.p12")
        KeyStore.getInstance("PKCS12").apply {
            load(null)
            setKeyEntry("ftp", pair.private, "pw".toCharArray(), arrayOf(cert))
            file.outputStream().use { store(it, "pw".toCharArray()) }
        }
        return file
    }

    private fun ftp(protocol: Protocol, user: String = "linus", password: String? = "secret") = FtpFileSystem(
        Connection(id = "ftp", name = "ftp", protocol = protocol, host = "127.0.0.1", port = port, username = user, password = password),
        knownHosts,
    )

    /** Shared scenario: upload a tree and a large file, browse, rename, delete, download again. */
    private suspend fun roundTrip(fs: FtpFileSystem) {
        val phone = InMemoryFileSystem("phone").apply {
            write("/Photos/a.jpg", "jpeg bytes")
            write("/Photos/trip/b.txt", "notes")
        }
        val big = Random(2).nextBytes(2 * 1024 * 1024 + 3)
        phone.openOutput("/big.bin").use { it.write(big) }
        val back = InMemoryFileSystem("back")
        val registry = FileSystemRegistry().apply { register(phone); register(fs); register(back) }
        val transfer = FileTransfer(registry)

        transfer.run(listOf(Location("phone", "/Photos"), Location("phone", "/big.bin")), Location(fs.id, "/"), move = false) {}
        assertEquals("notes", File(root, "Photos/trip/b.txt").readText())
        assertArrayEquals(big, File(root, "big.bin").readBytes())
        assertEquals(listOf("Photos", "big.bin"), fs.list("/").map { it.name }.sorted())
        assertTrue(fs.stat("/Photos").isDirectory)
        assertEquals(big.size.toLong(), fs.stat("/big.bin").size)

        transfer.run(listOf(Location(fs.id, "/big.bin")), Location("back", "/"), move = false) {}
        assertArrayEquals(big, back.openInput("/big.bin").readBytes())

        fs.createDirectory("/new")
        fs.createFile("/new/empty.txt")
        fs.rename("/new", "/renamed")
        assertTrue(fs.exists("/renamed/empty.txt"))
        fs.delete("/Photos")
        assertFalse(File(root, "Photos").exists())
        try {
            fs.stat("/nope")
            fail("missing file found")
        } catch (e: NotFoundFsException) {
            // expected
        }
        fs.close()
    }

    @Test
    fun plainFtp() = runTest {
        start(tls = false)
        roundTrip(ftp(Protocol.FTP))
    }

    @Test
    fun ftpsAsksToTrustTheCertificateThenWorks() = runTest {
        start(tls = true)
        val fs = ftp(Protocol.FTPS)
        try {
            fs.list("/")
            fail("connected to an untrusted FTPS server")
        } catch (e: UntrustedHostKeyException) {
            assertFalse(e.changed)
            knownHosts.trust(e.host, e.port, e.key)
        }
        roundTrip(fs)
    }

    /** Many short TLS data connections in a row: catches close-handshake races on upload. */
    @Test
    fun manySmallFtpsUploads() = runTest {
        start(tls = true)
        val fs = ftp(Protocol.FTPS)
        try {
            fs.list("/")
        } catch (e: UntrustedHostKeyException) {
            knownHosts.trust(e.host, e.port, e.key)
        }
        repeat(25) { i -> fs.openOutput("/f$i.txt").use { it.write("file $i".toByteArray()) } }
        assertEquals(25, fs.list("/").size)
        assertEquals("file 24", File(root, "f24.txt").readText())
        fs.close()
    }

    @Test
    fun ftpsRejectsChangedCertificate() = runTest {
        start(tls = true)
        knownHosts.trust("127.0.0.1", port, byteArrayOf(9, 9, 9))
        try {
            ftp(Protocol.FTPS).list("/")
            fail("accepted a changed certificate")
        } catch (e: UntrustedHostKeyException) {
            assertTrue(e.changed)
        }
    }

    @Test
    fun passwordsAndAnonymous() = runTest {
        start(tls = false, anonymous = true)
        try {
            ftp(Protocol.FTP, password = "wrong").list("/")
            fail("wrong password accepted")
        } catch (e: PasswordRequiredFsException) {
            assertTrue(e.wrongPassword)
        }
        val ask = ftp(Protocol.FTP, password = null)
        try {
            ask.list("/")
            fail("connected without a password")
        } catch (e: PasswordRequiredFsException) {
            assertFalse(e.wrongPassword)
        }
        ask.setPassword("secret".toCharArray())
        ask.list("/")
        ask.close()

        File(root, "public.txt").writeText("hi")
        val anon = ftp(Protocol.FTP, user = "", password = null)
        assertEquals(listOf("public.txt"), anon.list("/").map { it.name })
        anon.close()
    }
}
