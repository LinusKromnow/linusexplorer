package io.github.linuskromnow.linusexplorer.fs.properties

import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest

enum class ChecksumAlgorithm(val label: String, internal val jcaName: String) {
    MD5("MD5", "MD5"),
    SHA1("SHA-1", "SHA-1"),
    SHA256("SHA-256", "SHA-256"),
}

object Checksums {
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Hashes the file at [path] in a single streaming pass, so large files (and encrypted vault content)
     * never sit in memory. [onProgress] receives the bytes read so far. Results are lowercase hex.
     * Cancel the calling coroutine to stop.
     */
    suspend fun compute(
        fs: VirtualFileSystem,
        path: String,
        algorithms: Collection<ChecksumAlgorithm> = ChecksumAlgorithm.entries,
        onProgress: (bytesRead: Long) -> Unit = {},
    ): Map<ChecksumAlgorithm, String> = withContext(Dispatchers.IO) {
        val digests = algorithms.associateWith { MessageDigest.getInstance(it.jcaName) }
        fs.openInput(path).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digests.values.forEach { it.update(buffer, 0, read) }
                total += read
                onProgress(total)
            }
        }
        digests.mapValues { (_, digest) -> digest.digest().joinToString("") { "%02x".format(it) } }
    }
}
