package io.github.linuskromnow.linusexplorer.data

import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.local.LocalFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.Connection
import io.github.linuskromnow.linusexplorer.fs.vault.VaultFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SavedFoldersTest {
    @get:Rule val temp = TemporaryFolder()

    private val file by lazy { File(temp.root, "saved_folders.bin") }
    private fun local(path: String) = Location(LocalFileSystem.ID, path)

    @Test
    fun bookmarksSurviveReload() {
        val remote = Location(Connection.fsIdFor("c1"), "/home/linus")
        SavedFolders(file).apply {
            addBookmark(Bookmark(local("/sdcard/Projects"), "Projects"))
            addBookmark(Bookmark(remote, "linus"))
            addBookmark(Bookmark(local("/sdcard/Projects"), "Again")) // already bookmarked
        }
        val reloaded = SavedFolders(file)
        assertEquals(listOf(Bookmark(local("/sdcard/Projects"), "Projects"), Bookmark(remote, "linus")), reloaded.data.value.bookmarks)
        assertTrue(reloaded.isBookmarked(remote))

        reloaded.removeBookmark(remote)
        assertFalse(SavedFolders(file).isBookmarked(remote))
    }

    @Test
    fun recentsAreNewestFirstWithoutDuplicatesAndCapped() {
        val folders = SavedFolders(file)
        folders.recordVisit(local("/a"))
        folders.recordVisit(local("/b"))
        folders.recordVisit(local("/a"))
        assertEquals(listOf(local("/a"), local("/b")), folders.data.value.recents)

        repeat(SavedFolders.MAX_RECENTS + 5) { folders.recordVisit(local("/dir$it")) }
        val recents = SavedFolders(file).data.value.recents
        assertEquals(SavedFolders.MAX_RECENTS, recents.size)
        assertEquals(local("/dir${SavedFolders.MAX_RECENTS + 4}"), recents.first())

        folders.clearRecents()
        assertTrue(SavedFolders(file).data.value.recents.isEmpty())
    }

    /** Vault folder names must never end up outside the vault, and archive views don't outlive the app. */
    @Test
    fun ignoresVaultAndArchives() {
        val folders = SavedFolders(file)
        val vault = Location(VaultFileSystem.ID, "/secret")
        folders.recordVisit(vault)
        folders.recordVisit(Location("archive:local:/sdcard/a.zip", "/"))
        folders.addBookmark(Bookmark(vault, "secret"))
        assertEquals(SavedFoldersData(), folders.data.value)
        assertFalse(file.exists())
    }

    @Test
    fun forgetDropsADeletedConnection() {
        val gone = Connection.fsIdFor("gone")
        val folders = SavedFolders(file)
        folders.addBookmark(Bookmark(Location(gone, "/"), "server"))
        folders.addBookmark(Bookmark(local("/keep"), "keep"))
        folders.recordVisit(Location(gone, "/x"))
        folders.recordVisit(local("/keep"))
        folders.forget(gone)
        assertEquals(SavedFoldersData(listOf(Bookmark(local("/keep"), "keep")), listOf(local("/keep"))), SavedFolders(file).data.value)
    }

    @Test
    fun damagedFileIsSetAside() {
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(SavedFoldersData(), SavedFolders(file).data.value)
        assertTrue(File(file.path + ".unreadable").exists())
    }
}
