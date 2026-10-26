package io.github.linuskromnow.linusexplorer.tasks

import io.github.linuskromnow.linusexplorer.fs.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteEditsTest {
    @get:Rule val temp = TemporaryFolder()

    private val store by lazy { File(temp.root, "remote_edits.bin") }
    private val cache by lazy { temp.newFolder("open") }
    private val source = Location("net:server", "/docs/report.txt")

    /** A copy the way the browser makes them: one sub-folder per opened file. */
    private fun copy(name: String, text: String) =
        File(File(cache, name.substringBefore('.')).apply { mkdirs() }, name).apply { writeText(text) }

    private fun File.edit(text: String) {
        writeText(text)
        setLastModified(lastModified() + 2_000) // file systems with coarse timestamps
    }

    @Test
    fun editsAreNoticedAndSurviveRestarts() {
        val local = copy("report.txt", "v1")
        RemoteEdits(store).track(source, local, remoteModified = 42)
        assertTrue(RemoteEdits(store).changed().isEmpty())

        local.edit("v2 with more text")
        val edit = RemoteEdits(store).changed().single()
        assertEquals(source, edit.source)
        assertEquals(42L, edit.remoteModified)
    }

    @Test
    fun uploadingResetsTheBaselineAndLaterEditsShowAgain() {
        val local = copy("report.txt", "v1")
        val edits = RemoteEdits(store)
        edits.track(source, local, remoteModified = 1)
        local.edit("v2")
        edits.markUploaded(edits.changed().single(), remoteModified = 7)
        assertTrue(edits.changed().isEmpty())

        local.edit("v3 again")
        assertEquals(7L, RemoteEdits(store).changed().single().remoteModified)
    }

    @Test
    fun forgetStopsTracking() {
        val local = copy("report.txt", "v1")
        val edits = RemoteEdits(store)
        edits.track(source, local, remoteModified = 0)
        local.edit("changed")
        edits.forget(edits.changed().single())
        assertTrue(RemoteEdits(store).changed().isEmpty())
    }

    @Test
    fun cleanCacheKeepsUnsavedWork() {
        val edited = copy("edited.txt", "a")
        val untouched = copy("untouched.txt", "b")
        val untracked = copy("photo.jpg", "c") // e.g. a decrypted vault file
        val edits = RemoteEdits(store)
        edits.track(Location("net:s", "/edited.txt"), edited, 0)
        edits.track(Location("net:s", "/untouched.txt"), untouched, 0)
        edited.edit("a, edited")

        edits.cleanCache(cache, keepUnchanged = true) // vault lock: other apps may still have copies open
        assertTrue(untouched.exists())
        assertFalse(untracked.exists())

        edits.cleanCache(cache, keepUnchanged = false) // app start
        assertTrue(edited.exists())
        assertFalse(untouched.exists())
        assertEquals(listOf("edited.txt"), RemoteEdits(store).changed().map { it.name })
    }
}
