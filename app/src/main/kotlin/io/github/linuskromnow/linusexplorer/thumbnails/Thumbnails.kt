package io.github.linuskromnow.linusexplorer.thumbnails

import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FileKind
import io.github.linuskromnow.linusexplorer.fs.local.LocalFileSystem

enum class ThumbnailType { IMAGE, VIDEO, APK }

/** Coil model: what to draw a thumbnail for. Kept free of Android types so the policy is unit-testable. */
data class ThumbnailSource(
    val fsId: String,
    val path: String,
    val type: ThumbnailType,
    val lastModified: Long,
    val size: Long,
) {
    val isLocal: Boolean get() = fsId == LocalFileSystem.ID

    /** Changes when the file does, so edited files get a fresh thumbnail. */
    val cacheKey: String get() = "thumb:$fsId:$path:$lastModified:$size"

    /** Decrypted vault or archive content must never reach the unencrypted disk cache. */
    val allowDiskCache: Boolean get() = isLocal
}

/** Decides which entries get a thumbnail instead of the generic file-type icon. */
object Thumbnails {
    /** Formats Android's bitmap decoders handle (SVG, RAW and TIFF are not among them). */
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")

    /** Images from non-local file systems are decoded from a stream; skip very large ones. */
    const val MAX_STREAMED_IMAGE_BYTES = 32L * 1024 * 1024

    /**
     * Local files can use any thumbnail type. Other file systems (vault, archives) only get images,
     * since video frames and APK metadata need a real file on disk.
     */
    fun sourceFor(fsId: String, entry: FileEntry): ThumbnailSource? {
        if (entry.isDirectory || entry.size <= 0) return null
        val local = fsId == LocalFileSystem.ID
        val type = when {
            entry.extension in imageExtensions -> {
                if (!local && entry.size > MAX_STREAMED_IMAGE_BYTES) return null
                ThumbnailType.IMAGE
            }
            local && FileKind.of(entry) == FileKind.VIDEO -> ThumbnailType.VIDEO
            local && entry.extension == "apk" -> ThumbnailType.APK
            else -> return null
        }
        return ThumbnailSource(fsId, entry.path, type, entry.lastModified, entry.size)
    }
}
