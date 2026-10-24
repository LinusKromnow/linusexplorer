package io.github.linuskromnow.linusexplorer.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

open class AgeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The passphrase did not unlock the file (or the file was encrypted to someone else). */
class AgeNoMatchException : AgeException("Wrong password")

/**
 * The age v1 payload: a 16-byte nonce followed by the STREAM construction, i.e. 64 KiB chunks
 * sealed with ChaCha20-Poly1305 under HKDF-SHA256(fileKey, nonce, "payload").
 * See https://age-encryption.org/v1. Also used on its own for vault objects.
 */
object AgePayload {
    internal const val CHUNK_SIZE = 64 * 1024
    internal const val TAG_SIZE = 16
    private const val NONCE_SIZE = 16

    /** Writes the payload nonce and returns a stream that encrypts into [output]; close it to finish. */
    fun encrypt(output: OutputStream, fileKey: ByteArray): OutputStream {
        val nonce = AgeCrypto.random(NONCE_SIZE)
        output.write(nonce)
        return ChunkEncryptor(output, AgeCrypto.hkdf(fileKey, nonce, "payload"))
    }

    /** Reads the payload nonce from [input] and returns a stream of authenticated plaintext. */
    fun decrypt(input: InputStream, fileKey: ByteArray): InputStream {
        val nonce = AgeCrypto.readExactly(input, NONCE_SIZE) ?: throw AgeException("Truncated payload nonce")
        return ChunkDecryptor(input, AgeCrypto.hkdf(fileKey, nonce, "payload"))
    }

    internal fun chunkNonce(counter: Long, last: Boolean): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[10 - i] = (counter ushr (8 * i)).toByte()
        nonce[11] = if (last) 1 else 0
        return nonce
    }

    /** Buffers one chunk so the final chunk can be flagged as last when the stream is closed. */
    private class ChunkEncryptor(private val output: OutputStream, private val key: ByteArray) : OutputStream() {
        private val buffer = ByteArray(CHUNK_SIZE)
        private var length = 0
        private var counter = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "stream closed" }
            var offset = off
            var remaining = len
            while (remaining > 0) {
                if (length == CHUNK_SIZE) seal(last = false) // only now do we know more data follows
                val count = minOf(remaining, CHUNK_SIZE - length)
                System.arraycopy(b, offset, buffer, length, count)
                length += count
                offset += count
                remaining -= count
            }
        }

        override fun flush() = output.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                seal(last = true)
                output.flush()
            } finally {
                output.close()
            }
        }

        private fun seal(last: Boolean) {
            output.write(AgeCrypto.seal(key, chunkNonce(counter++, last), buffer, 0, length))
            length = 0
        }
    }

    private class ChunkDecryptor(private val input: InputStream, private val key: ByteArray) : InputStream() {
        private val sealed = ByteArray(CHUNK_SIZE + TAG_SIZE)
        private var plain = ByteArray(0)
        private var position = 0
        private var counter = 0L
        private var finished = false
        private var lookahead = -1

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position == plain.size) {
                if (!nextChunk()) return -1
            }
            val count = minOf(len, plain.size - position)
            System.arraycopy(plain, position, b, off, count)
            position += count
            return count
        }

        override fun close() = input.close()

        private fun nextChunk(): Boolean {
            if (finished) return false
            var size = 0
            if (lookahead >= 0) {
                sealed[0] = lookahead.toByte()
                size = 1
                lookahead = -1
            }
            size += AgeCrypto.readUpTo(input, sealed, size, sealed.size - size)
            val last = if (size < sealed.size) {
                true
            } else {
                // A full chunk is the last one only if nothing follows it.
                lookahead = input.read()
                lookahead < 0
            }
            if (size < TAG_SIZE) throw AgeException("Truncated payload")
            if (last && size == TAG_SIZE && counter > 0) throw AgeException("Empty final chunk")
            plain = AgeCrypto.open(key, chunkNonce(counter++, last), sealed, 0, size)
                ?: throw AgeException("Payload authentication failed; the file is damaged")
            position = 0
            finished = last
            return true
        }
    }
}

/** Primitive helpers shared by the age header, payload and the vault. */
internal object AgeCrypto {
    private val secureRandom = SecureRandom()

    fun random(size: Int) = ByteArray(size).also { secureRandom.nextBytes(it) }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info.toByteArray()))
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, off: Int = 0, len: Int = plain.size): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(cipher.getOutputSize(len))
        val written = cipher.processBytes(plain, off, len, out, 0)
        cipher.doFinal(out, written)
        return out
    }

    /** Returns null if authentication fails. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, off: Int = 0, len: Int = sealed.size): ByteArray? {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(cipher.getOutputSize(len))
        return try {
            val written = cipher.processBytes(sealed, off, len, out, 0)
            cipher.doFinal(out, written)
            out
        } catch (e: InvalidCipherTextException) {
            null
        }
    }

    fun readUpTo(input: InputStream, buffer: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val read = input.read(buffer, off + total, len - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    fun readExactly(input: InputStream, size: Int): ByteArray? {
        val buffer = ByteArray(size)
        return if (readUpTo(input, buffer, 0, size) == size) buffer else null
    }
}
