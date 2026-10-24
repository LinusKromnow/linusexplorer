package io.github.linuskromnow.linusexplorer

import android.app.Application
import android.content.Context
import io.github.linuskromnow.linusexplorer.data.UserPreferences
import io.github.linuskromnow.linusexplorer.fs.FileSystemRegistry
import io.github.linuskromnow.linusexplorer.fs.FileTransfer
import io.github.linuskromnow.linusexplorer.fs.local.LocalFileSystem

class LinusExplorerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** App-wide singletons, wired by hand to keep the dependency graph obvious. */
class AppContainer(context: Context) {
    val fileSystems = FileSystemRegistry().apply { register(LocalFileSystem()) }
    val fileTransfer = FileTransfer(fileSystems)
    val preferences = UserPreferences(context)
}
