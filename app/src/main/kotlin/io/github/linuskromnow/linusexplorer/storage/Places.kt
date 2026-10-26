package io.github.linuskromnow.linusexplorer.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import java.io.File

/** An entry in the navigation drawer. */
data class Place(val label: String, val path: String, val kind: Kind) {
    enum class Kind { INTERNAL, REMOVABLE, ROOT, FOLDER }

    val isVolume: Boolean get() = kind == Kind.INTERNAL || kind == Kind.REMOVABLE
}

object Places {
    private val shortcutFolders = listOf(
        Environment.DIRECTORY_DOWNLOADS,
        Environment.DIRECTORY_DCIM,
        Environment.DIRECTORY_PICTURES,
        Environment.DIRECTORY_DOCUMENTS,
        Environment.DIRECTORY_MUSIC,
        Environment.DIRECTORY_MOVIES,
    )

    @Suppress("DEPRECATION") // Only the path is used; access goes through All files access.
    fun primaryStoragePath(): String = Environment.getExternalStorageDirectory().path

    fun all(context: Context): List<Place> {
        val volumes = volumes(context)
        // Apps usually may not list "/" itself; an entry that only says "Permission denied" is noise.
        val root = Place(context.getString(R.string.place_root), VfsPaths.ROOT, Place.Kind.ROOT)
            .takeIf { File(VfsPaths.ROOT).listFiles() != null }
        val primary = primaryStoragePath()
        val shortcuts = shortcutFolders
            .map { File(primary, it) }
            .filter { it.isDirectory }
            .map { Place(it.name, it.path, Place.Kind.FOLDER) }
        return volumes + listOfNotNull(root) + shortcuts
    }

    fun volumes(context: Context): List<Place> {
        val storageManager = context.getSystemService(StorageManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return storageManager.storageVolumes
                .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
                .mapNotNull { volume ->
                    val dir = volume.directory ?: return@mapNotNull null
                    Place(volume.getDescription(context), dir.path, kindOf(volume.isRemovable))
                }
        }
        // Before Android 11 the volume root is derived from the app's per-volume files directory.
        return context.getExternalFilesDirs(null).filterNotNull().mapNotNull { appDir ->
            val root = appDir.path.substringBefore("/Android/data/", missingDelimiterValue = "")
            if (root.isEmpty()) return@mapNotNull null
            val volume = storageManager.getStorageVolume(appDir)
            Place(volume?.getDescription(context) ?: File(root).name, root, kindOf(volume?.isRemovable == true))
        }.distinctBy { it.path }
    }

    private fun kindOf(removable: Boolean) = if (removable) Place.Kind.REMOVABLE else Place.Kind.INTERNAL
}
