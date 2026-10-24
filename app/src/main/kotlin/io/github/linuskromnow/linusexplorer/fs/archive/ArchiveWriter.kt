package io.github.linuskromnow.linusexplorer.fs.archive

import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.ProgressTracker
import io.github.linuskromnow.linusexplorer.fs.TransferProgress
import io.github.linuskromnow.linusexplorer.fs.TransferTotals
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import io.github.linuskromnow.linusexplorer.fs.copyFileContents
import io.github.linuskromnow.linusexplorer.fs.countTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Date

/** Packs files from any [VirtualFileSystem] into a new archive on local disk. */
object ArchiveWriter {

    /**
     * Writes [sources] (each with the file system it lives in) into [output]. [password] is only
     * supported for formats with [ArchiveFormat.canEncrypt]. A failed or cancelled run deletes [output].
     */
    suspend fun create(
        sources: List<Pair<VirtualFileSystem, FileEntry>>,
        output: File,
        format: ArchiveFormat,
        password: CharArray?,
        onProgress: (TransferProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(format.canCreate) { "Cannot create ${format.label} archives" }
        if (password != null && !format.canEncrypt) throw FsException("${format.label} archives cannot be password-protected")

        val totals = TransferTotals()
        for ((fs, entry) in sources) countTree(fs, entry, totals)
        val tracker = ProgressTracker(totals.files, totals.bytes, onProgress)
        try {
            when (format) {
                ArchiveFormat.ZIP -> writeZip(sources, output, password, tracker)
                ArchiveFormat.SEVEN_Z -> writeSevenZ(sources, output, tracker)
                ArchiveFormat.TAR -> writeTar(sources, output, tracker) { it }
                ArchiveFormat.TAR_GZ -> writeTar(sources, output, tracker, ::GzipCompressorOutputStream)
                ArchiveFormat.TAR_BZ2 -> writeTar(sources, output, tracker, ::BZip2CompressorOutputStream)
                ArchiveFormat.TAR_XZ -> writeTar(sources, output, tracker, ::XZCompressorOutputStream)
                else -> error("unreachable: $format")
            }
        } catch (t: Throwable) {
            output.delete()
            throw t
        }
    }

    private suspend fun writeZip(
        sources: List<Pair<VirtualFileSystem, FileEntry>>,
        output: File,
        password: CharArray?,
        tracker: ProgressTracker,
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output)), password).use { zip ->
            walk(sources) { fs, entry, name ->
                val params = ZipParameters().apply {
                    fileNameInZip = if (entry.isDirectory) "$name/" else name
                    lastModifiedFileTime = entry.lastModified.takeIf { it > 0 } ?: System.currentTimeMillis()
                    if (password != null && !entry.isDirectory) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                }
                zip.putNextEntry(params)
                if (!entry.isDirectory) copyFileContents(fs, entry, zip, tracker)
                zip.closeEntry()
            }
        }
    }

    private suspend fun writeSevenZ(
        sources: List<Pair<VirtualFileSystem, FileEntry>>,
        output: File,
        tracker: ProgressTracker,
    ) {
        SevenZOutputFile(output).use { sevenZ ->
            val adapter = object : OutputStream() {
                override fun write(b: Int) = sevenZ.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = sevenZ.write(b, off, len)
            }
            walk(sources) { fs, entry, name ->
                val archiveEntry = SevenZArchiveEntry().apply {
                    this.name = name
                    isDirectory = entry.isDirectory
                    if (entry.lastModified > 0) lastModifiedDate = Date(entry.lastModified)
                }
                sevenZ.putArchiveEntry(archiveEntry)
                if (!entry.isDirectory) copyFileContents(fs, entry, adapter, tracker)
                sevenZ.closeArchiveEntry()
            }
            sevenZ.finish()
        }
    }

    private suspend fun writeTar(
        sources: List<Pair<VirtualFileSystem, FileEntry>>,
        output: File,
        tracker: ProgressTracker,
        compress: (OutputStream) -> OutputStream,
    ) {
        TarArchiveOutputStream(compress(BufferedOutputStream(FileOutputStream(output)))).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            walk(sources) { fs, entry, name ->
                val tarEntry = TarArchiveEntry(if (entry.isDirectory) "$name/" else name).apply {
                    if (!entry.isDirectory) size = entry.size
                    if (entry.lastModified > 0) setModTime(entry.lastModified)
                }
                tar.putArchiveEntry(tarEntry)
                if (!entry.isDirectory) copyFileContents(fs, entry, tar, tracker)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
    }

    /** Visits every source recursively with its "/"-separated path inside the archive. */
    private suspend fun walk(
        sources: List<Pair<VirtualFileSystem, FileEntry>>,
        visit: suspend (VirtualFileSystem, FileEntry, String) -> Unit,
    ) {
        suspend fun visitTree(fs: VirtualFileSystem, entry: FileEntry, name: String) {
            currentCoroutineContext().ensureActive()
            visit(fs, entry, name)
            if (entry.isDirectory && !entry.isSymlink) {
                for (child in fs.list(entry.path)) visitTree(fs, child, "$name/${child.name}")
            }
        }
        for ((fs, entry) in sources) visitTree(fs, entry, entry.name)
    }
}
