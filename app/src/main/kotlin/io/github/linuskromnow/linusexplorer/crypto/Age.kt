package io.github.linuskromnow.linusexplorer.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.CharBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * Passphrase-encrypted files in the age v1 format (https://age-encryption.org/v1), readable by
 * `age -d`, rage and other implementations. Only the scrypt recipient type is supported.
 */
object Age {
    const val EXTENSION = ".age"

    /**
     * scrypt work factor for new files. age's desktop default is 18; 16 (64 MiB, a fraction of a
     * second on phones) keeps unlocking snappy. Any work factor up to [MAX_WORK_FACTOR] decrypts.
     */
    const val DEFAULT_WORK_FACTOR = 16

    /** Highest work factor accepted when decrypting, per the age spec. */
    private const val MAX_WORK_FACTOR = 22

    private const val VERSION_LINE = "age-encryption.org/v1"
    private const val SCRYPT_LABEL = "age-encryption.org/v1/scrypt"
    private const val FILE_KEY_SIZE = 16
    private const val SALT_SIZE = 16
    private const val COLUMNS = 64

    private val b64Encoder = Base64.getEncoder().withoutPadding()
    private val b64Decoder = Base64.getDecoder()

    /** Writes the age header to [output] and returns the stream to write plaintext into. Close it to finish. */
    fun encrypt(output: OutputStream, passphrase: CharArray, workFactor: Int = DEFAULT_WORK_FACTOR): OutputStream {
        val fileKey = AgeCrypto.random(FILE_KEY_SIZE)
        val salt = AgeCrypto.random(SALT_SIZE)
        val wrapped = AgeCrypto.seal(scryptKey(passphrase, salt, workFactor), ByteArray(12), fileKey)
        val header = "$VERSION_LINE\n-> scrypt ${b64(salt)} $workFactor\n${wrapBody(b64(wrapped))}---"
        val mac = headerMac(fileKey, header.toByteArray())
        output.write("$header ${b64(mac)}\n".toByteArray())
        return AgePayload.encrypt(output, fileKey)
    }

    /**
     * Parses the header from [input] and returns a stream of decrypted, authenticated plaintext.
     * Throws [AgeNoMatchException] for a wrong passphrase and [AgeException] for malformed files.
     */
    fun decrypt(input: InputStream, passphrase: CharArray): InputStream {
        val header = HeaderReader(input)
        if (header.line() != VERSION_LINE) throw AgeException("Not an age file")

        val stanzas = mutableListOf<Pair<List<String>, ByteArray>>()
        var line = header.line()
        while (line.startsWith("-> ")) {
            val args = line.removePrefix("-> ").split(' ')
            if (args.any { it.isEmpty() || !it.all { c -> c in '!'..'~' } }) throw AgeException("Malformed stanza")
            val body = ByteArrayOutputStream()
            while (true) {
                val bodyLine = header.line()
                if (bodyLine.length > COLUMNS) throw AgeException("Malformed stanza body")
                body.write(b64Decode(bodyLine))
                if (bodyLine.length < COLUMNS) break
            }
            stanzas += args to body.toByteArray()
            line = header.line()
        }
        if (!line.startsWith("--- ")) throw AgeException("Malformed header")
        val headerBytes = header.consumed().let { it.copyOf(it.size - (line.length + 1) + 3) } // up to and including "---"
        val mac = b64Decode(line.removePrefix("--- "))

        val scrypt = stanzas.filter { it.first.first() == "scrypt" }
        if (scrypt.isNotEmpty() && stanzas.size != 1) throw AgeException("A passphrase file must have exactly one recipient")
        val (args, body) = scrypt.singleOrNull() ?: throw AgeNoMatchException()
        if (args.size != 3) throw AgeException("Malformed scrypt stanza")
        val salt = b64Decode(args[1])
        if (salt.size != SALT_SIZE) throw AgeException("Malformed scrypt salt")
        val workFactor = parseWorkFactor(args[2])
        if (body.size != FILE_KEY_SIZE + AgePayload.TAG_SIZE) throw AgeException("Malformed scrypt body")

        val fileKey = AgeCrypto.open(scryptKey(passphrase, salt, workFactor), ByteArray(12), body)
            ?: throw AgeNoMatchException()
        if (!MessageDigest.isEqual(headerMac(fileKey, headerBytes), mac)) throw AgeException("Header authentication failed")
        return AgePayload.decrypt(input, fileKey)
    }

    private fun parseWorkFactor(text: String): Int {
        if (text.isEmpty() || text.length > 2 || !text.all { it in '0'..'9' } || text.startsWith('0')) {
            throw AgeException("Malformed scrypt work factor")
        }
        val value = text.toInt()
        if (value !in 1..MAX_WORK_FACTOR) throw AgeException("scrypt work factor $value is out of range")
        return value
    }

    private fun scryptKey(passphrase: CharArray, salt: ByteArray, workFactor: Int): ByteArray {
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val password = ByteArray(encoded.remaining()).also { encoded.get(it) }
        return try {
            Scrypt.derive(password, SCRYPT_LABEL.toByteArray() + salt, 1 shl workFactor, 8, 1, 32)
        } catch (e: OutOfMemoryError) {
            throw AgeException("Not enough memory for scrypt work factor $workFactor", e)
        } finally {
            password.fill(0)
        }
    }

    private fun headerMac(fileKey: ByteArray, header: ByteArray): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(AgeCrypto.hkdf(fileKey, ByteArray(0), "header")))
        hmac.update(header, 0, header.size)
        return ByteArray(hmac.macSize).also { hmac.doFinal(it, 0) }
    }

    private fun b64(bytes: ByteArray): String = b64Encoder.encodeToString(bytes)

    /** Strict, canonical, unpadded standard base64 as required by age. */
    private fun b64Decode(text: String): ByteArray {
        if ('=' in text || '\r' in text || '\n' in text) throw AgeException("Malformed base64")
        val bytes = try {
            b64Decoder.decode(text)
        } catch (e: IllegalArgumentException) {
            throw AgeException("Malformed base64", e)
        }
        if (b64(bytes) != text) throw AgeException("Non-canonical base64")
        return bytes
    }

    /** Stanza bodies are wrapped at 64 columns and always end with a line shorter than that. */
    private fun wrapBody(encoded: String): String =
        (encoded.chunked(COLUMNS).let { if (encoded.length % COLUMNS == 0) it + "" else it })
            .joinToString(separator = "") { "$it\n" }

    /** Reads "\n"-terminated header lines byte by byte, so the payload is left untouched in [input]. */
    private class HeaderReader(private val input: InputStream) {
        private val consumed = ByteArrayOutputStream()

        fun line(): String {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw AgeException("Truncated header")
                consumed.write(b)
                if (b == '\n'.code) break
                line.write(b)
                if (line.size() > 1024) throw AgeException("Header line too long")
            }
            return line.toString(Charsets.US_ASCII.name())
        }

        fun consumed(): ByteArray = consumed.toByteArray()
    }
}
