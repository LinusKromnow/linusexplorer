package io.github.linuskromnow.linusexplorer.fs.vault

import io.github.linuskromnow.linusexplorer.fs.FileEncryption
import io.github.linuskromnow.linusexplorer.fs.FileSystemRegistry
import io.github.linuskromnow.linusexplorer.fs.FileTransfer
import io.github.linuskromnow.linusexplorer.fs.InMemoryFileSystem
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultFileSystemTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dir by lazy { temp.newFolder("vault") }
    private fun vault() = VaultFileSystem(dir, "Vault")

    private suspend fun expectPasswordPrompt(wrong: Boolean, block: suspend () -> Unit) {
        try {
            block()
            fail("expected a password prompt")
        } catch (e: PasswordRequiredFsException) {
            assertEquals(wrong, e.wrongPassword)
            assertEquals(VaultFileSystem.ID, e.fsId)
        }
    }

    @Test
    fun moveFilesInLockAndUnlock() = runTest {
        val phone = InMemoryFileSystem("phone").apply {
            write("/DCIM/private.jpg", "very private photo")
            write("/DCIM/sub/notes.txt", "diary")
        }
        val vault = vault()
        vault.create("pw".toCharArray())
        val registry = FileSystemRegistry().apply { register(phone); register(vault) }

        FileTransfer(registry).run(listOf(Location("phone", "/DCIM")), Location(VaultFileSystem.ID, "/"), move = true) {}
        assertFalse(phone.allPaths().any { it.startsWith("/DCIM") })

        vault.lock()
        expectPasswordPrompt(wrong = false) { vault.list("/") }
        vault.setPassword("nope".toCharArray())
        expectPasswordPrompt(wrong = true) { vault.list("/") }

        // A fresh instance proves everything was persisted.
        val reopened = vault()
        reopened.setPassword("pw".toCharArray())
        assertEquals(listOf("private.jpg", "sub"), reopened.list("/DCIM").map { it.name }.sorted())
        assertEquals("diary", reopened.openInput("/DCIM/sub/notes.txt").use { String(it.readBytes()) })
        assertEquals(18L, reopened.stat("/DCIM/private.jpg").size)
    }

    @Test
    fun nothingReadableOnDisk() = runTest {
        val vault = vault()
        vault.create("pw".toCharArray())
        vault.createDirectory("/Taxes 2026")
        vault.openOutput("/Taxes 2026/secret-plans.txt").use { it.write("launch codes".toByteArray()) }
        val everything = dir.walkTopDown().filter { it.isFile }.joinToString("") { it.name + String(it.readBytes(), Charsets.ISO_8859_1) }
        for (needle in listOf("Taxes", "secret-plans", "launch codes")) {
            assertFalse("$needle leaked", needle in everything)
        }
    }

    @Test
    fun renameDeleteAndOverwrite() = runTest {
        val vault = vault()
        vault.create("pw".toCharArray())
        vault.createDirectory("/a")
        vault.openOutput("/a/f.txt").use { it.write("one".toByteArray()) }
        vault.openOutput("/a/f.txt").use { it.write("two".toByteArray()) }
        assertEquals(1, File(dir, "objects").listFiles()!!.size) // old version cleaned up

        vault.rename("/a", "/b")
        assertEquals("two", vault.openInput("/b/f.txt").use { String(it.readBytes()) })
        assertFalse(vault.exists("/a/f.txt"))

        vault.delete("/b")
        assertTrue(vault.list("/").isEmpty())
        assertEquals(0, File(dir, "objects").listFiles()!!.size)
    }

    @Test
    fun changePassword() = runTest {
        val vault = vault()
        vault.create("old".toCharArray())
        vault.createFile("/x")
        vault.changePassword("new".toCharArray())
        vault.lock()
        vault.setPassword("old".toCharArray())
        expectPasswordPrompt(wrong = true) { vault.list("/") }
        vault.setPassword("new".toCharArray())
        assertEquals(listOf("x"), vault.list("/").map { it.name })
    }

    @Test
    fun encryptAndDecryptSingleFiles() = runTest {
        val fs = InMemoryFileSystem().apply { write("/doc.pdf", "pdf bytes") }
        FileEncryption.encrypt(fs, listOf(fs.stat("/doc.pdf")), "pw".toCharArray(), deleteOriginals = true) {}
        assertFalse(fs.exists("/doc.pdf"))
        assertTrue(fs.read("/doc.pdf.age").startsWith("age-encryption.org/v1\n"))

        val out = FileEncryption.decrypt(fs, fs.stat("/doc.pdf.age"), fs, "/", "pw".toCharArray()) {}
        assertEquals("/doc.pdf", out)
        assertEquals("pdf bytes", fs.read(out))
    }
}
