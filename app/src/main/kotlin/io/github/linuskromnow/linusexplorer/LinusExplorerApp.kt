package io.github.linuskromnow.linusexplorer

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import io.github.linuskromnow.linusexplorer.data.UserPreferences
import io.github.linuskromnow.linusexplorer.fs.FileSystemRegistry
import io.github.linuskromnow.linusexplorer.fs.FileTransfer
import io.github.linuskromnow.linusexplorer.fs.local.LocalFileSystem
import io.github.linuskromnow.linusexplorer.fs.vault.VaultFileSystem
import io.github.linuskromnow.linusexplorer.security.VaultBiometrics
import io.github.linuskromnow.linusexplorer.tasks.TaskManager
import io.github.linuskromnow.linusexplorer.thumbnails.ThumbnailFetcher
import io.github.linuskromnow.linusexplorer.thumbnails.ThumbnailKeyer
import io.github.linuskromnow.linusexplorer.tasks.TransferService
import io.github.linuskromnow.linusexplorer.ui.browser.BrowserViewModel
import java.io.File

class LinusExplorerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        TransferService.createChannel(this)
        // Files decrypted or unpacked for other apps only need to live until the next start.
        Thread { File(cacheDir, BrowserViewModel.OPEN_CACHE).deleteRecursively() }.start()
    }
}

/** App-wide singletons, wired by hand to keep the dependency graph obvious. */
class AppContainer(context: Context) {
    val vault = VaultFileSystem(File(context.filesDir, "vault"), context.getString(R.string.vault_name))
    val vaultBiometrics = VaultBiometrics(File(context.filesDir, "vault_biometric.bin"))
    val fileSystems = FileSystemRegistry().apply {
        register(LocalFileSystem())
        register(vault)
    }
    val fileTransfer = FileTransfer(fileSystems)
    val preferences = UserPreferences(context)
    val tasks = TaskManager(context.applicationContext)

    /**
     * Thumbnail loader. No disk cache on purpose: the fetchers read local sources directly, and
     * decrypted vault content must stay out of unencrypted storage.
     */
    val imageLoader = ImageLoader.Builder(context.applicationContext)
        .components {
            add(ThumbnailKeyer)
            add(ThumbnailFetcher.Factory(fileSystems))
            add(VideoFrameDecoder.Factory())
        }
        .diskCache(null)
        .crossfade(true)
        .build()
}
