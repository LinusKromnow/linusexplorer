package io.github.linuskromnow.linusexplorer.data

import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.local.LocalFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.Connection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class Bookmark(val location: Location, val name: String)

data class SavedFoldersData(val bookmarks: List<Bookmark> = emptyList(), val recents: List<Location> = emptyList())

/**
 * Bookmarked and recently visited folders, kept in one small file.
 *
 * Only local folders and saved network connections are remembered: archive views are temporary,
 * and the vault's folder names must not leak outside the vault.
 */
class SavedFolders(private val file: File) {
    private val _data = MutableStateFlow(load())
    val data: StateFlow<SavedFoldersData> = _data.asStateFlow()

    fun isBookmarked(location: Location): Boolean = _data.value.bookmarks.any { it.location == location }

    @Synchronized
    fun addBookmark(bookmark: Bookmark) {
        if (!canSave(bookmark.location) || isBookmarked(bookmark.location)) return
        write(_data.value.let { it.copy(bookmarks = it.bookmarks + bookmark) })
    }

    @Synchronized
    fun removeBookmark(location: Location) {
        write(_data.value.let { data -> data.copy(bookmarks = data.bookmarks.filterNot { it.location == location }) })
    }

    /** Moves [location] to the front of the recent list. */
    @Synchronized
    fun recordVisit(location: Location) {
        if (!canSave(location)) return
        val recents = _data.value.recents
        if (recents.firstOrNull() == location) return
        val updated = (listOf(location) + recents.filterNot { it == location }).take(MAX_RECENTS)
        write(_data.value.copy(recents = updated))
    }

    @Synchronized
    fun clearRecents() {
        if (_data.value.recents.isNotEmpty()) write(_data.value.copy(recents = emptyList()))
    }

    /** Drops everything on [fsId], e.g. after its connection was deleted. */
    @Synchronized
    fun forget(fsId: String) {
        val data = _data.value
        val updated = SavedFoldersData(
            bookmarks = data.bookmarks.filterNot { it.location.fsId == fsId },
            recents = data.recents.filterNot { it.fsId == fsId },
        )
        if (updated != data) write(updated)
    }

    private fun write(data: SavedFoldersData) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeBytes(SavedFoldersCodec.encode(data))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        _data.value = data
    }

    /** A damaged file only costs the bookmarks and recents; it must not keep the app from starting. */
    private fun load(): SavedFoldersData {
        if (!file.exists()) return SavedFoldersData()
        return try {
            SavedFoldersCodec.decode(file.readBytes())
        } catch (e: Exception) {
            file.renameTo(File(file.path + ".unreadable"))
            SavedFoldersData()
        }
    }

    companion object {
        const val MAX_RECENTS = 10

        fun canSave(location: Location): Boolean =
            location.fsId == LocalFileSystem.ID || Connection.connectionIdOf(location.fsId) != null
    }
}

/** Binary format for [SavedFolders] (kept free of Android APIs so it is unit-testable). */
internal object SavedFoldersCodec {
    private const val MAGIC = 0x4C454653 // "LEFS"
    private const val VERSION = 1

    fun encode(data: SavedFoldersData): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(data.bookmarks.size)
            for (bookmark in data.bookmarks) {
                writeLocation(out, bookmark.location)
                out.writeUTF(bookmark.name)
            }
            out.writeInt(data.recents.size)
            data.recents.forEach { writeLocation(out, it) }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): SavedFoldersData = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (input.readInt() != MAGIC) throw FsException("Saved folders are damaged")
        val version = input.readInt()
        if (version > VERSION) throw FsException("Saved folders need a newer LinusExplorer (format $version)")
        val bookmarks = List(input.readInt()) { Bookmark(readLocation(input), input.readUTF()) }
        val recents = List(input.readInt()) { readLocation(input) }
        SavedFoldersData(bookmarks, recents)
    }

    private fun writeLocation(out: DataOutputStream, location: Location) {
        out.writeUTF(location.fsId)
        out.writeUTF(location.path)
    }

    private fun readLocation(input: DataInputStream) = Location(input.readUTF(), input.readUTF())
}
