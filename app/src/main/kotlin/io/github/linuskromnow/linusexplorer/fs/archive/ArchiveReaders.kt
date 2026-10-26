package io.github.linuskromnow.linusexplorer.fs.archive

import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

/** One raw entry as stored in an archive; [name] is the archive's own (relative) path. */
internal data class RawEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val encrypted: Boolean = false,
)

/** Blocking, stateless access to one archive format. Each call opens and closes the archive. */
internal interface ArchiveReader {
    fun list(file: File, password: CharArray?): List<RawEntry>

    /** The returned stream owns all resources and must be closed by the caller. */
    fun open(file: File, password: CharArray?, name: String): InputStream

    companion object {
        fun forFormat(format: ArchiveFormat): ArchiveReader = when (format) {
            ArchiveFormat.ZIP -> ZipReader
            ArchiveFormat.SEVEN_Z -> SevenZReader
            ArchiveFormat.TAR -> TarReader { it }
            ArchiveFormat.TAR_GZ -> TarReader(::gunzip)
            ArchiveFormat.TAR_BZ2 -> TarReader(::bunzip2)
            ArchiveFormat.TAR_XZ -> TarReader(::unxz)
            ArchiveFormat.GZIP -> SingleFileReader(::gunzip)
            ArchiveFormat.BZIP2 -> SingleFileReader(::bunzip2)
            ArchiveFormat.XZ -> SingleFileReader(::unxz)
        }

        private fun gunzip(input: InputStream): InputStream = GzipCompressorInputStream(input, true)
        private fun bunzip2(input: InputStream): InputStream = BZip2CompressorInputStream(input, true)
        private fun unxz(input: InputStream): InputStream = XZCompressorInputStream(input, true)
    }
}

/**
 * An archive opened for browsing, kept open while it is being viewed so that opening one file
 * doesn't re-read the whole table of contents.
 */
internal interface ArchiveHandle : Closeable {
    fun list(): List<RawEntry>

    /** The caller closes the returned stream; the handle stays open. */
    fun open(name: String): InputStream
}

/** Formats without a reusable table of contents: each call opens the archive again. */
internal class StatelessHandle(
    private val reader: ArchiveReader,
    private val file: File,
    private val password: CharArray?,
) : ArchiveHandle {
    override fun list() = reader.list(file, password)
    override fun open(name: String) = reader.open(file, password, name)
    override fun close() = Unit
}

/**
 * A ZIP read through Apache Commons Compress, which keeps the central directory in memory and
 * reads entries by seeking, from a local file or a [RangeChannel] over the network. It can't
 * decrypt; encrypted ZIPs on the device go through [ZipReader] (zip4j) instead.
 */
internal class SeekableZipHandle private constructor(private val zip: CommonsZipFile) : ArchiveHandle {
    override fun list(): List<RawEntry> = zip.entries.asSequence().map {
        RawEntry(it.name, it.isDirectory, it.size, it.time.coerceAtLeast(0), it.generalPurposeBit.usesEncryption())
    }.toList()

    override fun open(name: String): InputStream {
        val entry = zip.getEntry(name) ?: throw NotFoundFsException(name)
        if (entry.generalPurposeBit.usesEncryption()) throw PasswordRequiredFsException(null, wrongPassword = false)
        val unsupported = FsException("“${entry.name.substringAfterLast('/')}” uses an unsupported ZIP compression method")
        if (!zip.canReadEntryData(entry)) throw unsupported
        return try {
            zip.getInputStream(entry)
        } catch (e: LinkageError) {
            throw unsupported // e.g. Zstandard, whose optional library isn't bundled
        }
    }

    override fun close() = zip.close()

    companion object {
        // Without ignoreLocalFileHeader, opening visits every entry's local header: that reads the
        // whole ZIP over the network. With it, an entry's header is read when the entry is opened.
        fun open(file: File) = SeekableZipHandle(CommonsZipFile.builder().setFile(file).setIgnoreLocalFileHeader(true).get())
        fun open(channel: SeekableByteChannel) =
            SeekableZipHandle(CommonsZipFile.builder().setSeekableByteChannel(channel).setIgnoreLocalFileHeader(true).get())
    }
}

/** Closes [resource] together with the stream, and lets readers translate read errors. */
internal class OwningInputStream(
    input: InputStream,
    private val resource: Closeable?,
    private val mapError: (IOException) -> IOException = { it },
) : FilterInputStream(input) {
    override fun read(): Int = try {
        super.read()
    } catch (e: IOException) {
        throw mapError(e)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = try {
        super.read(b, off, len)
    } catch (e: IOException) {
        throw mapError(e)
    }

    override fun close() {
        try {
            super.close()
        } finally {
            resource?.close()
        }
    }
}

private fun openBuffered(file: File): InputStream = BufferedInputStream(FileInputStream(file))

internal object ZipReader : ArchiveReader {
    override fun list(file: File, password: CharArray?): List<RawEntry> = guard {
        ZipFile(file, password).use { zip ->
            zip.fileHeaders.map {
                RawEntry(it.fileName, it.isDirectory, it.uncompressedSize, it.lastModifiedTimeEpoch, it.isEncrypted)
            }
        }
    }

    override fun open(file: File, password: CharArray?, name: String): InputStream {
        val zip = ZipFile(file, password)
        try {
            val header = zip.getFileHeader(name) ?: throw NotFoundFsException(name)
            if (header.isEncrypted && password == null) throw PasswordRequiredFsException(null, wrongPassword = false)
            val input = guard { zip.getInputStream(header) }
            return OwningInputStream(input, zip, ::translate)
        } catch (t: Throwable) {
            zip.close()
            throw t
        }
    }

    private fun translate(e: IOException): IOException =
        if (e is ZipException && e.type == ZipException.Type.WRONG_PASSWORD) {
            PasswordRequiredFsException(null, wrongPassword = true, e)
        } else {
            e
        }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: ZipException) {
        throw translate(e)
    }
}

internal object SevenZReader : ArchiveReader {
    override fun list(file: File, password: CharArray?): List<RawEntry> = openArchive(file, password).use { archive ->
        archive.entries.map { entry ->
            RawEntry(
                name = entry.name ?: file.nameWithoutExtension,
                isDirectory = entry.isDirectory,
                size = entry.size,
                lastModified = if (entry.hasLastModifiedDate) entry.lastModifiedDate.time else 0,
                encrypted = entry.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true,
            )
        }
    }

    override fun open(file: File, password: CharArray?, name: String): InputStream {
        val archive = openArchive(file, password)
        try {
            val entry = archive.entries.firstOrNull { (it.name ?: file.nameWithoutExtension) == name }
                ?: throw NotFoundFsException(name)
            return OwningInputStream(archive.getInputStream(entry), archive) { translate(it, password) }
        } catch (t: Throwable) {
            archive.close()
            throw t
        }
    }

    private fun openArchive(file: File, password: CharArray?): SevenZFile = try {
        SevenZFile.builder().setFile(file).apply { if (password != null) setPassword(password) }.get()
    } catch (e: IOException) {
        throw translate(e, password)
    }

    private fun translate(e: IOException, password: CharArray?): IOException = when {
        e is PasswordRequiredException -> PasswordRequiredFsException(null, wrongPassword = false, e)
        // 7z has no password check value; a wrong key surfaces as corrupt data.
        password != null && e !is FsException -> PasswordRequiredFsException(null, wrongPassword = true, e)
        else -> e
    }
}

internal class TarReader(private val decompress: (InputStream) -> InputStream) : ArchiveReader {
    override fun list(file: File, password: CharArray?): List<RawEntry> = openTar(file).use { tar ->
        generateSequence { tar.nextEntry }
            .filter { it.isDirectory || it.isFile }
            .map { RawEntry(it.name, it.isDirectory, it.size, it.lastModifiedDate.time) }
            .toList()
    }

    override fun open(file: File, password: CharArray?, name: String): InputStream {
        val tar = openTar(file)
        try {
            while (true) {
                val entry = tar.nextEntry ?: throw NotFoundFsException(name)
                if (entry.name == name) return OwningInputStream(tar, null) // reads stop at the entry's end
            }
        } catch (t: Throwable) {
            tar.close()
            throw t
        }
    }

    private fun openTar(file: File) = TarArchiveInputStream(decompress(openBuffered(file)))
}

/** A plain compressed file (".gz", ".xz", ...) shown as an archive holding one file. */
internal class SingleFileReader(private val decompress: (InputStream) -> InputStream) : ArchiveReader {
    override fun list(file: File, password: CharArray?): List<RawEntry> =
        listOf(RawEntry(innerName(file), isDirectory = false, size = 0, lastModified = file.lastModified()))

    override fun open(file: File, password: CharArray?, name: String): InputStream {
        if (name != innerName(file)) throw NotFoundFsException(name)
        return decompress(openBuffered(file))
    }

    private fun innerName(file: File) = file.name.substringBeforeLast('.')
}
