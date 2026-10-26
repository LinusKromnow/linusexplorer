package io.github.linuskromnow.linusexplorer.fs.vault

import io.github.linuskromnow.linusexplorer.crypto.Age
import io.github.linuskromnow.linusexplorer.crypto.AgeCrypto
import io.github.linuskromnow.linusexplorer.crypto.AgeNoMatchException
import io.github.linuskromnow.linusexplorer.crypto.AgePayload
import io.github.linuskromnow.linusexplorer.fs.AlreadyExistsFsException
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.PasswordProtected
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Password-protected, encrypted storage inside the app's private directory.
 *
 * Layout of [root]:
 * - `key.age`: a random 256-bit master key, age-encrypted with the vault password
 * - `index`: names, folders, sizes and dates of everything inside (encrypted with the master key)
 * - `objects/<random id>`: file contents (encrypted with the master key)
 *
 * File names never appear on disk. Unlock by calling [setPassword]; operations on a locked vault
 * throw [PasswordRequiredFsException] so the UI can prompt.
 */
class VaultFileSystem(
    private val root: File,
    override val displayName: String,
) : VirtualFileSystem, PasswordProtected {
    override val id = ID
    override val rootPath = VfsPaths.ROOT

    private val keyFile = File(root, "key.age")
    private val indexFile = File(root, "index")
    private val objectsDir = File(root, "objects")

    private val lock = Any()
    @Volatile private var masterKey: ByteArray? = null
    @Volatile private var pendingPassword: CharArray? = null
    private val entries = HashMap<String, VaultEntry>() // guarded by lock

    val isCreated: Boolean get() = keyFile.exists()
    val isUnlocked: Boolean get() = masterKey != null

    /** Sets up a new, empty vault and leaves it unlocked. */
    suspend fun create(password: CharArray) = io {
        synchronized(lock) {
            if (isCreated) throw FsException("The vault already exists")
            objectsDir.mkdirs()
            val key = AgeCrypto.random(32)
            writeKeyFile(key, password)
            entries.clear()
            entries[VfsPaths.ROOT] = VaultEntry(VfsPaths.ROOT, isDirectory = true, size = 0, lastModified = 0, objectId = "")
            masterKey = key
            saveIndex()
        }
    }

    /** Re-encrypts the master key with [newPassword]; contents are untouched. Vault must be unlocked. */
    suspend fun changePassword(newPassword: CharArray) {
        val key = unlockedKey()
        io { synchronized(lock) { writeKeyFile(key, newPassword) } }
    }

    override fun setPassword(password: CharArray) {
        pendingPassword = password
    }

    /**
     * Unlocks with a master key recovered another way (fingerprint). Throws
     * [PasswordRequiredFsException] if the key cannot decrypt this vault's index.
     */
    suspend fun unlockWithKey(key: ByteArray) = io {
        synchronized(lock) {
            if (masterKey == null) {
                try {
                    loadIndex(key)
                } catch (e: IOException) {
                    entries.clear()
                    throw PasswordRequiredFsException(ID, wrongPassword = true, e)
                }
                masterKey = key.copyOf()
            }
        }
    }

    /** A copy of the master key (null while locked), e.g. to protect it with a fingerprint key. */
    fun masterKeyCopy(): ByteArray? = masterKey?.copyOf()

    /** Writes the whole vault (already encrypted) as one zip; it can be restored with [restoreFrom]. */
    suspend fun backupTo(output: OutputStream) = io {
        synchronized(lock) {
            ZipOutputStream(output.buffered()).use { zip ->
                zip.setLevel(0) // ciphertext doesn't compress
                for (file in listOf(keyFile, indexFile) + (objectsDir.listFiles()?.toList() ?: emptyList())) {
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Replaces this vault with a backup made by [backupTo]. The vault is locked afterwards and opens
     * with the password it had when the backup was made.
     */
    suspend fun restoreFrom(input: InputStream) = io {
        synchronized(lock) {
            val parent = root.absoluteFile.parentFile
            val staging = File(parent, "${root.name}.restore").apply { deleteRecursively(); mkdirs() }
            try {
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        val allowed = name == "key.age" || name == "index" || BACKUP_OBJECT.matches(name)
                        if (!allowed || entry.isDirectory) throw FsException("This is not a LinusExplorer vault backup")
                        val target = File(staging, name).apply { parentFile?.mkdirs() }
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
                if (!File(staging, "key.age").exists() || !File(staging, "index").exists()) {
                    throw FsException("This is not a LinusExplorer vault backup")
                }
                File(staging, "objects").mkdirs()
                lockLocked()
                val old = File(parent, "${root.name}.old").apply { deleteRecursively() }
                if (root.exists()) Files.move(root.toPath(), old.toPath())
                Files.move(staging.toPath(), root.toPath())
                old.deleteRecursively()
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    private val _locks = MutableStateFlow(0)

    /** Counts [lock] calls, so every open view of the vault can leave it. */
    val locks: StateFlow<Int> = _locks.asStateFlow()

    /** Forgets the master key and the decrypted index. */
    fun lock() {
        synchronized(lock) { lockLocked() }
        _locks.value++
    }

    /** Caller holds [lock]. */
    private fun lockLocked() {
        masterKey?.fill(0)
        masterKey = null
        pendingPassword = null
        entries.clear()
    }

    override suspend fun list(path: String): List<FileEntry> = withEntries {
        val dir = VfsPaths.normalize(path)
        val entry = entries[dir] ?: throw NotFoundFsException(path)
        if (!entry.isDirectory) throw FsException("“${VfsPaths.name(path)}” is not a folder")
        entries.values.filter { it.path != VfsPaths.ROOT && VfsPaths.parent(it.path) == dir }.map { it.toFileEntry() }
    }

    override suspend fun stat(path: String): FileEntry = withEntries {
        (entries[VfsPaths.normalize(path)] ?: throw NotFoundFsException(path)).toFileEntry()
    }

    override suspend fun exists(path: String): Boolean = withEntries { VfsPaths.normalize(path) in entries }

    override suspend fun createDirectory(path: String) = mutate {
        val p = requireNewChild(path)
        entries[p] = VaultEntry(p, isDirectory = true, size = 0, lastModified = System.currentTimeMillis(), objectId = "")
    }

    override suspend fun createFile(path: String) {
        withEntries { requireNewChild(path) }
        openOutput(path).close()
    }

    override suspend fun delete(path: String) = mutate {
        val p = VfsPaths.normalize(path)
        if (p == VfsPaths.ROOT) throw FsException("The vault itself cannot be deleted")
        if (p !in entries) throw NotFoundFsException(path)
        val doomed = entries.keys.filter { VfsPaths.isAncestorOrSelf(p, it) }
        for (key in doomed) {
            val entry = entries.remove(key)!!
            if (!entry.isDirectory) File(objectsDir, entry.objectId).delete()
        }
    }

    override suspend fun rename(path: String, newPath: String) = mutate {
        val from = VfsPaths.normalize(path)
        if (from == VfsPaths.ROOT || from !in entries) throw NotFoundFsException(path)
        val to = requireNewChild(newPath)
        if (VfsPaths.isAncestorOrSelf(from, to)) throw FsException("Cannot move a folder into itself")
        val moved = entries.keys.filter { VfsPaths.isAncestorOrSelf(from, it) }
        for (key in moved) {
            val entry = entries.remove(key)!!
            val newKey = to + key.removePrefix(from)
            entries[newKey] = entry.copy(path = newKey)
        }
    }

    override suspend fun moveWithin(path: String, newPath: String): Boolean {
        rename(path, newPath)
        return true
    }

    override suspend fun openInput(path: String): InputStream {
        val key = unlockedKey()
        val entry = withEntries { entries[VfsPaths.normalize(path)] ?: throw NotFoundFsException(path) }
        if (entry.isDirectory) throw FsException("“${VfsPaths.name(path)}” is a folder")
        return io { AgePayload.decrypt(FileInputStream(File(objectsDir, entry.objectId)).buffered(), key) }
    }

    /** The file appears in the vault (replacing an older version) once the returned stream is closed. */
    override suspend fun openOutput(path: String): OutputStream {
        val key = unlockedKey()
        val p = VfsPaths.normalize(path)
        withEntries {
            val parent = VfsPaths.parent(p)?.let { entries[it] }
            if (parent == null || !parent.isDirectory) throw NotFoundFsException(VfsPaths.parent(p) ?: p)
            if (entries[p]?.isDirectory == true) throw AlreadyExistsFsException(p)
        }
        val objectId = UUID.randomUUID().toString()
        val objectFile = File(objectsDir, objectId)
        val encrypted = io { AgePayload.encrypt(FileOutputStream(objectFile).buffered(), key) }
        return object : FilterOutputStream(encrypted) {
            private var size = 0L
            private var closed = false

            override fun write(b: Int) {
                out.write(b)
                size++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
                size += len
            }

            override fun close() {
                if (closed) return
                closed = true
                out.close()
                synchronized(lock) {
                    if (masterKey == null) {
                        objectFile.delete() // locked while writing; the index can't be updated
                        throw IOException("The vault was locked during the transfer")
                    }
                    val old = entries[p]
                    entries[p] = VaultEntry(p, isDirectory = false, size = size, lastModified = System.currentTimeMillis(), objectId = objectId)
                    saveIndex()
                    if (old != null && !old.isDirectory) File(objectsDir, old.objectId).delete()
                }
            }
        }
    }

    // region internals

    private fun requireNewChild(path: String): String {
        val p = VfsPaths.normalize(path)
        if (p in entries) throw AlreadyExistsFsException(p)
        val parent = VfsPaths.parent(p)?.let { entries[it] }
        if (parent == null || !parent.isDirectory) throw NotFoundFsException(VfsPaths.parent(p) ?: p)
        return p
    }

    private suspend fun <T> withEntries(block: () -> T): T {
        unlockedKey()
        return synchronized(lock) { block() }
    }

    private suspend fun mutate(block: () -> Unit) {
        unlockedKey()
        io {
            synchronized(lock) {
                block()
                saveIndex()
            }
        }
    }

    /** Returns the master key, unlocking with the pending password if needed. */
    private suspend fun unlockedKey(): ByteArray {
        masterKey?.let { return it }
        if (!isCreated) throw FsException("The vault has not been set up")
        return io {
            synchronized(lock) {
                masterKey ?: run {
                    val password = pendingPassword ?: throw PasswordRequiredFsException(ID, wrongPassword = false)
                    pendingPassword = null
                    val key = try {
                        FileInputStream(keyFile).buffered().use { Age.decrypt(it, password).use { s -> s.readBytes() } }
                    } catch (e: AgeNoMatchException) {
                        throw PasswordRequiredFsException(ID, wrongPassword = true, e)
                    }
                    loadIndex(key)
                    masterKey = key
                    key
                }
            }
        }
    }

    private fun writeKeyFile(key: ByteArray, password: CharArray) {
        root.mkdirs()
        val temp = File(root, "key.age.tmp")
        FileOutputStream(temp).use { out -> Age.encrypt(out, password).use { it.write(key) } }
        replace(temp, keyFile)
    }

    /** Caller holds [lock]. Writes to a temp file first so a crash never leaves a half-written index. */
    private fun saveIndex() {
        val key = masterKey ?: throw PasswordRequiredFsException(ID, wrongPassword = false)
        val temp = File(root, "index.tmp")
        FileOutputStream(temp).use { out ->
            AgePayload.encrypt(out.buffered(), key).use { it.write(VaultIndexCodec.encode(entries.values)) }
        }
        replace(temp, indexFile)
    }

    private fun loadIndex(key: ByteArray) {
        entries.clear()
        val loaded = FileInputStream(indexFile).buffered().use { AgePayload.decrypt(it, key).use { s -> s.readBytes() } }
        VaultIndexCodec.decode(loaded).associateByTo(entries) { it.path }
        entries.getOrPut(VfsPaths.ROOT) { VaultEntry(VfsPaths.ROOT, isDirectory = true, size = 0, lastModified = 0, objectId = "") }
    }

    /** Atomically swaps [source] into place so readers see either the old or the new file, never half of one. */
    private fun replace(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // endregion

    companion object {
        const val ID = "vault"
        const val BACKUP_EXTENSION = ".lxvault"
        private val BACKUP_OBJECT = Regex("objects/[0-9a-f-]{36}")
    }
}

internal data class VaultEntry(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    /** Name of the encrypted content file in `objects/`; empty for folders. */
    val objectId: String,
) {
    fun toFileEntry() = FileEntry(VfsPaths.name(path), path, isDirectory, size, lastModified)
}

/** Compact binary format for the vault index (kept free of Android APIs so it is unit-testable). */
internal object VaultIndexCodec {
    private const val MAGIC = 0x4C455649 // "LEVI"
    private const val VERSION = 1

    fun encode(entries: Collection<VaultEntry>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(entries.size)
            for (e in entries) {
                out.writeUTF(e.path)
                out.writeBoolean(e.isDirectory)
                out.writeLong(e.size)
                out.writeLong(e.lastModified)
                out.writeUTF(e.objectId)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): List<VaultEntry> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (input.readInt() != MAGIC) throw FsException("The vault index is damaged")
        val version = input.readInt()
        if (version != VERSION) throw FsException("Vault format $version needs a newer LinusExplorer")
        List(input.readInt()) {
            VaultEntry(input.readUTF(), input.readBoolean(), input.readLong(), input.readLong(), input.readUTF())
        }
    }
}
