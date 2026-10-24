package io.github.linuskromnow.linusexplorer.fs.vault

import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VaultBackupTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun backupAndRestoreOnAnotherInstall() = runTest {
        val original = VaultFileSystem(temp.newFolder("a", "vault"), "Vault")
        original.create("pw".toCharArray())
        original.createDirectory("/docs")
        original.openOutput("/docs/passport.pdf").use { it.write("scan".toByteArray()) }
        val backup = ByteArrayOutputStream().also { original.backupTo(it) }.toByteArray()

        // A fresh install that already has a different vault.
        val other = VaultFileSystem(temp.newFolder("b", "vault"), "Vault")
        other.create("other".toCharArray())
        other.restoreFrom(ByteArrayInputStream(backup))
        assertFalse(other.isUnlocked)
        other.setPassword("pw".toCharArray())
        assertEquals("scan", other.openInput("/docs/passport.pdf").use { String(it.readBytes()) })
        assertFalse(File(temp.root, "b/vault.old").exists())
    }

    @Test
    fun unlockWithMasterKey() = runTest {
        val vault = VaultFileSystem(temp.newFolder("vault"), "Vault")
        vault.create("pw".toCharArray())
        vault.createFile("/x")
        val key = vault.masterKeyCopy()!!
        vault.lock()
        try {
            vault.unlockWithKey(ByteArray(32))
            fail("wrong key accepted")
        } catch (e: PasswordRequiredFsException) {
            assertTrue(e.wrongPassword)
        }
        vault.unlockWithKey(key)
        assertEquals(listOf("x"), vault.list("/").map { it.name })
    }

    @Test
    fun rejectsForeignOrMaliciousZips() = runTest {
        val dir = temp.newFolder("vault")
        val vault = VaultFileSystem(dir, "Vault")
        vault.create("pw".toCharArray())
        val evil = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("../../escape.txt"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        try {
            vault.restoreFrom(ByteArrayInputStream(evil))
            fail("malicious backup accepted")
        } catch (e: FsException) {
            // expected
        }
        assertFalse(File(temp.root, "escape.txt").exists())
        vault.setPassword("pw".toCharArray())
        assertTrue(vault.list("/").isEmpty()) // the existing vault is untouched
    }
}
