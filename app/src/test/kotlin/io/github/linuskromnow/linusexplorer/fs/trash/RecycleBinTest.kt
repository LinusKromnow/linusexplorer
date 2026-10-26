package io.github.linuskromnow.linusexplorer.fs.trash

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class RecycleBinTest {
    @get:Rule val temp = TemporaryFolder()

    private val volume by lazy { temp.newFolder("storage") }
    private var now = 1_000_000_000_000L
    private val bin by lazy { RecycleBin(volumeRoots = { listOf(volume.path) }, clock = { now }) }

    private fun file(relative: String, text: String = relative) =
        File(volume, relative).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun trashAndRestoreAFile() = runTest {
        val photo = file("DCIM/photo.jpg", "pixels")
        assertTrue(bin.trash(photo.path))
        assertFalse(photo.exists())
        assertTrue(File(volume, "${RecycleBin.BIN_NAME}/.nomedia").exists())

        val item = bin.list().single()
        assertEquals(photo.path, item.originalPath)
        assertEquals("photo.jpg", item.name)
        assertEquals(6L, item.size)
        assertEquals(now, item.deletedAt)

        assertEquals(photo.path, bin.restore(item))
        assertEquals("pixels", photo.readText())
        assertTrue(bin.list().isEmpty())
        assertEquals(1, bin.restores.value)
    }

    @Test
    fun restoreRecreatesMissingParentsAndAvoidsOverwriting() = runTest {
        val notes = file("Docs/Work/notes.txt", "old")
        bin.trash(notes.path)
        File(volume, "Docs").deleteRecursively()
        bin.restore(bin.list().single())
        assertEquals("old", notes.readText())

        bin.trash(notes.path)
        file("Docs/Work/notes.txt", "new") // something else took the name meanwhile
        val restored = bin.restore(bin.list().single())
        assertEquals(File(volume, "Docs/Work/notes (1).txt").path, restored)
        assertEquals("old", File(restored).readText())
        assertEquals("new", notes.readText())
    }

    @Test
    fun foldersGoInWhole() = runTest {
        file("Project/src/main.kt")
        file("Project/README")
        val project = File(volume, "Project")
        bin.trash(project.path)
        val item = bin.list().single()
        assertTrue(item.isDirectory)
        bin.restore(item)
        assertEquals("Project/src/main.kt", File(project, "src/main.kt").readText())
    }

    @Test
    fun refusesPathsOutsideVolumesTheVolumeItselfAndTheBin() = runTest {
        val outside = temp.newFile("elsewhere.txt")
        assertFalse(bin.trash(outside.path))
        assertTrue(outside.exists())
        assertFalse(bin.accepts(volume.path))

        bin.trash(file("a.txt").path)
        val trashedCopy = bin.list().single().file
        assertFalse(bin.accepts(trashedCopy.path)) // deleting inside the bin is for good
    }

    @Test
    fun deleteEmptyAndPurge() = runTest {
        bin.trash(file("old.txt").path)
        now += TimeUnit.DAYS.toMillis(RecycleBin.RETENTION_DAYS + 1L)
        bin.trash(file("recent.txt").path)
        bin.trash(file("other.txt").path)

        assertEquals(1, bin.purgeOlderThan())
        assertEquals(listOf("other.txt", "recent.txt"), bin.list().map { it.name }.sorted())

        bin.delete(bin.list().first { it.name == "recent.txt" })
        assertEquals(listOf("other.txt"), bin.list().map { it.name })

        bin.empty()
        assertTrue(bin.list().isEmpty())
        assertTrue(File(volume, "${RecycleBin.BIN_NAME}/files").list().isNullOrEmpty())
    }

    @Test
    fun picksTheInnermostVolume() = runTest {
        val sdCard = File(volume, "sd").apply { mkdirs() }
        val nested = RecycleBin(volumeRoots = { listOf(volume.path, sdCard.path) })
        nested.trash(file("sd/song.mp3").path)
        assertTrue(File(sdCard, RecycleBin.BIN_NAME).isDirectory)
        assertFalse(File(volume, RecycleBin.BIN_NAME).exists())
    }
}
