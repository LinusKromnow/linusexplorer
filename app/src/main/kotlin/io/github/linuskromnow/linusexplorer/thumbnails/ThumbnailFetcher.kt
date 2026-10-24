package io.github.linuskromnow.linusexplorer.thumbnails

import android.webkit.MimeTypeMap
import androidx.core.graphics.drawable.toBitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.Dimension
import io.github.linuskromnow.linusexplorer.fs.FileSystemRegistry
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.source

object ThumbnailKeyer : Keyer<ThumbnailSource> {
    override fun key(data: ThumbnailSource, options: Options): String = data.cacheKey
}

/**
 * Feeds [ThumbnailSource]s to Coil. Images and videos are handed over as a source for Coil's own
 * decoders (downsampled to the requested size); APKs are turned into their launcher icon.
 * Local files are read by path, everything else through the [io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem] input stream, so
 * nothing is ever written to disk here.
 */
class ThumbnailFetcher(
    private val data: ThumbnailSource,
    private val options: Options,
    private val fileSystems: FileSystemRegistry,
) : Fetcher {
    override suspend fun fetch(): FetchResult = when (data.type) {
        ThumbnailType.IMAGE -> if (data.isLocal) {
            file("image/${data.path.substringAfterLast('.').lowercase()}")
        } else {
            val stream = fileSystems[data.fsId].openInput(data.path)
            SourceFetchResult(
                source = ImageSource(stream.source().buffer(), FileSystem.SYSTEM),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        }
        ThumbnailType.VIDEO -> {
            val extension = data.path.substringAfterLast('.').lowercase()
            file(MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/$extension")
        }
        ThumbnailType.APK -> apkIcon()
    }

    private fun file(mimeType: String) = SourceFetchResult(
        source = ImageSource(data.path.toPath(), FileSystem.SYSTEM),
        mimeType = mimeType,
        dataSource = DataSource.DISK,
    )

    private fun apkIcon(): FetchResult {
        val context = options.context
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(data.path, 0)?.applicationInfo
            ?: error("Not a readable APK: ${data.path}")
        // Without these the icon would be resolved against the wrong (or no) resources.
        info.sourceDir = data.path
        info.publicSourceDir = data.path
        val px = (options.size.width as? Dimension.Pixels)?.px ?: DEFAULT_ICON_PX
        val bitmap = info.loadIcon(pm).toBitmap(px, px)
        return ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val fileSystems: FileSystemRegistry) : Fetcher.Factory<ThumbnailSource> {
        override fun create(data: ThumbnailSource, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbnailFetcher(data, options, fileSystems)
    }

    private companion object {
        const val DEFAULT_ICON_PX = 96
    }
}
