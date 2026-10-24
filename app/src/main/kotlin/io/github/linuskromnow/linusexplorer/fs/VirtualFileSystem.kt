package io.github.linuskromnow.linusexplorer.fs

import java.io.InputStream
import java.io.OutputStream

/**
 * Common contract for every storage backend (local disk, SFTP, FTP, SMB, WebDAV, cloud drives...).
 *
 * Paths are absolute and "/"-separated regardless of backend; see [VfsPaths].
 * Implementations throw [FsException] (or a subclass) for expected failures.
 */
interface VirtualFileSystem {
    /** Stable key used by [Location.fsId], e.g. "local" or a saved connection id. */
    val id: String
    val displayName: String
    val rootPath: String

    /** Read-only file systems (e.g. opened archives) reject every write operation. */
    val isReadOnly: Boolean get() = false

    suspend fun list(path: String): List<FileEntry>
    suspend fun stat(path: String): FileEntry
    suspend fun exists(path: String): Boolean

    /** Fails if [path] already exists. */
    suspend fun createDirectory(path: String)

    /** Creates an empty file; fails if [path] already exists. */
    suspend fun createFile(path: String)

    /** Deletes a file or a whole directory tree. Symbolic links are removed, never followed. */
    suspend fun delete(path: String)

    /** Renames or moves within this file system; fails if [newPath] exists. */
    suspend fun rename(path: String, newPath: String)

    /**
     * Cheap server-side move used by [FileTransfer]. Returns false when the backend cannot do it
     * (e.g. across mount points), in which case the caller falls back to copy + delete.
     */
    suspend fun moveWithin(path: String, newPath: String): Boolean = false

    suspend fun openInput(path: String): InputStream

    /** Creates or truncates the file at [path]. */
    suspend fun openOutput(path: String): OutputStream
}

open class FsException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

class NotFoundFsException(path: String, cause: Throwable? = null) :
    FsException("“${VfsPaths.name(path)}” does not exist", cause)

class AlreadyExistsFsException(path: String, cause: Throwable? = null) :
    FsException("“${VfsPaths.name(path)}” already exists", cause)

class AccessDeniedFsException(path: String, cause: Throwable? = null) :
    FsException("Permission denied: ${VfsPaths.name(path)}", cause)

class ReadOnlyFsException : FsException("This location is read-only")

/**
 * The file system [fsId] needs a (different) password; see [PasswordProtected].
 * [wrongPassword] is true when a password was supplied but rejected.
 */
class PasswordRequiredFsException(
    val fsId: String?,
    val wrongPassword: Boolean,
    cause: Throwable? = null,
) : FsException(if (wrongPassword) "Wrong password" else "Password required", cause)

/** A file system whose contents can only be read after [setPassword]. */
interface PasswordProtected {
    fun setPassword(password: CharArray)
}
