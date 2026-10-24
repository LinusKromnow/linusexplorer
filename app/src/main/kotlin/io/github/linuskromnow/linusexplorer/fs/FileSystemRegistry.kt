package io.github.linuskromnow.linusexplorer.fs

import java.util.concurrent.ConcurrentHashMap

/** Looks up live [VirtualFileSystem] instances by [Location.fsId]. */
class FileSystemRegistry {
    private val systems = ConcurrentHashMap<String, VirtualFileSystem>()

    /** Creates file systems that aren't registered yet on demand, e.g. saved network connections. */
    var resolver: ((String) -> VirtualFileSystem?)? = null

    fun register(fs: VirtualFileSystem) {
        systems[fs.id] = fs
    }

    fun unregister(id: String) {
        systems.remove(id)
    }

    fun find(id: String): VirtualFileSystem? = systems[id]

    operator fun get(id: String): VirtualFileSystem =
        systems[id]
            ?: resolver?.invoke(id)?.let { systems.putIfAbsent(id, it) ?: it }
            ?: throw FsException("Storage “$id” is not connected")
}
