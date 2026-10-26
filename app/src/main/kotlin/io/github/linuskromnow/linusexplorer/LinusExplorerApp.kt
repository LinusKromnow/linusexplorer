package io.github.linuskromnow.linusexplorer

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import io.github.linuskromnow.linusexplorer.data.SavedFolders
import io.github.linuskromnow.linusexplorer.fs.trash.RecycleBin
import io.github.linuskromnow.linusexplorer.storage.Places
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.github.linuskromnow.linusexplorer.data.UserPreferences
import io.github.linuskromnow.linusexplorer.fs.FileSystemRegistry
import io.github.linuskromnow.linusexplorer.fs.FileTransfer
import io.github.linuskromnow.linusexplorer.fs.local.LocalFileSystem
import io.github.linuskromnow.linusexplorer.fs.root.RootFileSystem
import io.github.linuskromnow.linusexplorer.fs.vault.VaultFileSystem
import io.github.linuskromnow.linusexplorer.security.VaultBiometrics
import io.github.linuskromnow.linusexplorer.tasks.RemoteEdits
import io.github.linuskromnow.linusexplorer.tasks.TaskManager
import io.github.linuskromnow.linusexplorer.thumbnails.ThumbnailFetcher
import io.github.linuskromnow.linusexplorer.thumbnails.ThumbnailKeyer
import io.github.linuskromnow.linusexplorer.tasks.TransferService
import io.github.linuskromnow.linusexplorer.ui.browser.BrowserViewModel
import io.github.linuskromnow.linusexplorer.ui.browser.Clipboard
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.linuskromnow.linusexplorer.fs.network.ConnectionStore
import io.github.linuskromnow.linusexplorer.fs.network.KnownHosts
import io.github.linuskromnow.linusexplorer.fs.network.NetworkFileSystems
import io.github.linuskromnow.linusexplorer.security.KeystoreSecretBox
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security

class LinusExplorerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // sshj needs the full BouncyCastle provider; Android ships a cut-down one under the same name.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
        container = AppContainer(this)
        TransferService.createChannel(this)
        // Files decrypted or unpacked for other apps only need to live until the next start.
        // Copies of remote files with edits not uploaded yet are kept for the upload prompt.
        Thread { container.remoteEdits.cleanCache(File(cacheDir, BrowserViewModel.OPEN_CACHE), keepUnchanged = false) }.start()
        // Without storage access yet the bins just look empty, so this is safe on the very first start.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { container.recycleBin.purgeOlderThan() } }
    }
}

/** App-wide singletons, wired by hand to keep the dependency graph obvious. */
class AppContainer(context: Context) {
    val vault = VaultFileSystem(File(context.filesDir, "vault"), context.getString(R.string.vault_name))
    val vaultBiometrics = VaultBiometrics(File(context.filesDir, "vault_biometric.bin"))
    val fileSystems = FileSystemRegistry().apply {
        register(LocalFileSystem())
        register(vault)
        register(RootFileSystem(context))
    }
    val network = NetworkFileSystems(
        registry = fileSystems,
        store = ConnectionStore(File(context.filesDir, "connections.bin"), KeystoreSecretBox("linusexplorer_connections")),
        knownHosts = KnownHosts(File(context.filesDir, "known_hosts")),
    ).also { network -> fileSystems.resolver = network::resolve }
    val fileTransfer = FileTransfer(fileSystems)
    val preferences = UserPreferences(context)
    val savedFolders = SavedFolders(File(context.filesDir, "saved_folders.bin"))
    val recycleBin = RecycleBin(volumeRoots = { Places.volumes(context).map { it.path } })
    val tasks = TaskManager(context.applicationContext)
    val remoteEdits = RemoteEdits(File(context.filesDir, "remote_edits.bin"))

    /** Shared by all tabs and panes, so files copied in one can be pasted in another. */
    val clipboard = MutableStateFlow<Clipboard?>(null)

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
