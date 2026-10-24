package io.github.linuskromnow.linusexplorer.thumbnails

import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.local.LocalFileSystem
import io.github.linuskromnow.linusexplorer.fs.vault.VaultFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailsTest {
    private fun file(name: String, size: Long = 1024, modified: Long = 1) =
        FileEntry(name, "/dir/$name", isDirectory = false, size = size, lastModified = modified)

    private val local = LocalFileSystem.ID
    private val vault = VaultFileSystem.ID

    @Test
    fun localImagesVideosAndApksQualify() {
        for (name in listOf("a.jpg", "a.JPEG", "a.png", "a.webp", "a.gif", "a.heic", "a.heif", "a.bmp")) {
            assertEquals(name, ThumbnailType.IMAGE, Thumbnails.sourceFor(local, file(name))?.type)
        }
        assertEquals(ThumbnailType.VIDEO, Thumbnails.sourceFor(local, file("clip.mp4"))?.type)
        assertEquals(ThumbnailType.VIDEO, Thumbnails.sourceFor(local, file("clip.mkv"))?.type)
        assertEquals(ThumbnailType.APK, Thumbnails.sourceFor(local, file("app.apk"))?.type)
    }

    @Test
    fun otherEntriesDoNot() {
        for (name in listOf("a.txt", "a.pdf", "a.svg", "a.tiff", "a.apks", "a.aab", "a.mp3", "noext")) {
            assertNull(name, Thumbnails.sourceFor(local, file(name)))
        }
        assertNull(Thumbnails.sourceFor(local, FileEntry("pics.jpg", "/pics.jpg", true, 0, 0)))
        assertNull(Thumbnails.sourceFor(local, file("empty.jpg", size = 0)))
    }

    @Test
    fun nonLocalFileSystemsOnlyGetImages() {
        for (fsId in listOf(vault, "archive:/sdcard/x.zip")) {
            assertEquals(ThumbnailType.IMAGE, Thumbnails.sourceFor(fsId, file("a.png"))?.type)
            assertNull(Thumbnails.sourceFor(fsId, file("a.mp4")))
            assertNull(Thumbnails.sourceFor(fsId, file("a.apk")))
        }
    }

    @Test
    fun hugeStreamedImagesAreSkippedButLocalOnesAreNot() {
        val huge = file("big.jpg", size = Thumbnails.MAX_STREAMED_IMAGE_BYTES + 1)
        assertNull(Thumbnails.sourceFor(vault, huge))
        assertNotNull(Thumbnails.sourceFor(local, huge))
    }

    private fun assertNotNull(value: Any?) = assertTrue(value != null)

    @Test
    fun onlyLocalSourcesMayUseTheDiskCache() {
        assertTrue(Thumbnails.sourceFor(local, file("a.png"))!!.allowDiskCache)
        assertFalse(Thumbnails.sourceFor(vault, file("a.png"))!!.allowDiskCache)
        assertFalse(Thumbnails.sourceFor("archive:/x.zip", file("a.png"))!!.allowDiskCache)
    }

    @Test
    fun cacheKeyChangesWithFileSystemPathAndContent() {
        val base = Thumbnails.sourceFor(local, file("a.png"))!!
        assertEquals(base.cacheKey, Thumbnails.sourceFor(local, file("a.png"))!!.cacheKey)
        assertNotEquals(base.cacheKey, Thumbnails.sourceFor(vault, file("a.png"))!!.cacheKey)
        assertNotEquals(base.cacheKey, Thumbnails.sourceFor(local, file("b.png"))!!.cacheKey)
        assertNotEquals(base.cacheKey, Thumbnails.sourceFor(local, file("a.png", modified = 2))!!.cacheKey)
        assertNotEquals(base.cacheKey, Thumbnails.sourceFor(local, file("a.png", size = 5))!!.cacheKey)
    }
}
