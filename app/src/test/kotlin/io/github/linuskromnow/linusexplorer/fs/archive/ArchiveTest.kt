package io.github.linuskromnow.linusexplorer.fs.archive

import io.github.linuskromnow.linusexplorer.fs.FileSystemRegistry
import io.github.linuskromnow.linusexplorer.fs.FileTransfer
import io.github.linuskromnow.linusexplorer.fs.InMemoryFileSystem
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.ReadOnlyFsException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val source = InMemoryFileSystem("src").apply {
        write("/docs/readme.txt", "hello archive")
        write("/docs/nested/deep.txt", "deep")
        mkdirs("/docs/empty")
        write("/single.txt", "solo")
    }

    private suspend fun pack(format: ArchiveFormat, password: String? = null): File {
        val output = File(temp.root, "out${format.extension}")
        val sources = listOf(source.stat("/docs"), source.stat("/single.txt")).map { source to it }
        ArchiveWriter.create(sources, output, format, password?.toCharArray()) {}
        return output
    }

    /** Extracts the whole archive into a fresh in-memory file system, as the app's "Extract" does. */
    private suspend fun extract(archive: ArchiveFileSystem): InMemoryFileSystem {
        val target = InMemoryFileSystem("dst")
        val registry = FileSystemRegistry().apply { register(archive); register(target) }
        val roots = archive.list("/").map { Location(archive.id, it.path) }
        FileTransfer(registry).run(roots, Location(target.id, "/"), move = false) {}
        return target
    }

    private fun assertRoundTrip(extracted: InMemoryFileSystem) {
        assertEquals("hello archive", extracted.read("/docs/readme.txt"))
        assertEquals("deep", extracted.read("/docs/nested/deep.txt"))
        assertEquals("solo", extracted.read("/single.txt"))
        assertTrue("/docs/empty" in extracted.allPaths())
    }

    @Test
    fun roundTripEveryCreatableFormat() = runTest {
        for (format in ArchiveFormat.creatable) {
            val file = pack(format)
            val archive = ArchiveFileSystem("a-$format", file, format)
            assertEquals(format.toString(), listOf("docs", "single.txt"), archive.list("/").map { it.name }.sorted())
            assertEquals(format.toString(), 13L, archive.stat("/docs/readme.txt").size)
            assertRoundTrip(extract(archive))
        }
    }

    @Test
    fun encryptedZipNeedsCorrectPassword() = runTest {
        val file = pack(ArchiveFormat.ZIP, password = "s3cret")
        val archive = ArchiveFileSystem("enc", file, ArchiveFormat.ZIP)

        // Listing works without a password; reading content does not.
        assertEquals(2, archive.list("/").size)
        try {
            archive.verifyPassword()
            fail("expected a password prompt")
        } catch (e: PasswordRequiredFsException) {
            assertFalse(e.wrongPassword)
            assertEquals("enc", e.fsId)
        }

        archive.setPassword("wrong".toCharArray())
        try {
            archive.verifyPassword()
            fail("wrong password accepted")
        } catch (e: PasswordRequiredFsException) {
            assertTrue(e.wrongPassword)
        }

        archive.setPassword("s3cret".toCharArray())
        archive.verifyPassword()
        assertRoundTrip(extract(archive))
    }

    @Test
    fun sevenZipCannotBeEncryptedOnCreate() = runTest {
        try {
            pack(ArchiveFormat.SEVEN_Z, password = "x")
            fail("expected failure")
        } catch (e: Exception) {
            assertFalse(File(temp.root, "out.7z").exists())
        }
    }

    @Test
    fun archivesAreReadOnly() = runTest {
        val archive = ArchiveFileSystem("ro", pack(ArchiveFormat.ZIP), ArchiveFormat.ZIP)
        try {
            archive.createDirectory("/new")
            fail("write accepted")
        } catch (e: ReadOnlyFsException) {
            // expected
        }
    }

    @Test
    fun indexCannotEscapeRoot() {
        val index = ArchiveIndex.build(
            listOf(
                RawEntry("../../etc/passwd", false, 4, 0),
                RawEntry("a\\b.txt", false, 1, 0),
            ),
        )
        assertEquals(setOf("/", "/etc", "/etc/passwd", "/a", "/a/b.txt"), index.entries.keys)
        assertNull(index.firstEncrypted)
    }

    @Test
    fun formatDetection() {
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.detect("backup.TAR.GZ"))
        assertEquals(ArchiveFormat.GZIP, ArchiveFormat.detect("log.gz"))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.detect("a.zip"))
        assertNull(ArchiveFormat.detect(".zip"))
        assertNull(ArchiveFormat.detect("movie.mp4"))
        assertEquals("backup", ArchiveFormat.baseName("backup.tar.gz"))
        assertEquals("notes", ArchiveFormat.baseName("notes"))
    }
}
