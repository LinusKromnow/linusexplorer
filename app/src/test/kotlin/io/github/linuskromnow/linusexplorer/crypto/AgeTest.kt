package io.github.linuskromnow.linusexplorer.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

class AgeTest {

    private fun encrypt(plain: ByteArray, passphrase: String, workFactor: Int = 10): ByteArray {
        val out = ByteArrayOutputStream()
        Age.encrypt(out, passphrase.toCharArray(), workFactor).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun decrypt(cipher: ByteArray, passphrase: String): ByteArray =
        Age.decrypt(ByteArrayInputStream(cipher), passphrase.toCharArray()).use { it.readBytes() }

    @Test
    fun roundTripAcrossChunkBoundaries() {
        val chunk = AgePayload.CHUNK_SIZE
        for (size in listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 7)) {
            val plain = Random(size).nextBytes(size)
            assertArrayEquals("size $size", plain, decrypt(encrypt(plain, "correct horse"), "correct horse"))
        }
    }

    @Test
    fun headerLooksLikeAge() {
        val text = String(encrypt("hi".toByteArray(), "pw"), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("age-encryption.org/v1\n-> scrypt "))
        assertTrue(text.contains("\n--- "))
    }

    @Test(expected = AgeNoMatchException::class)
    fun wrongPassphrase() {
        decrypt(encrypt("secret".toByteArray(), "right"), "wrong")
    }

    @Test
    fun tamperedPayloadIsRejected() {
        val cipher = encrypt(ByteArray(1000) { 1 }, "pw")
        cipher[cipher.size - 20] = (cipher[cipher.size - 20].toInt() xor 1).toByte()
        try {
            decrypt(cipher, "pw")
            fail("tampering not detected")
        } catch (e: AgeException) {
            // expected
        }
    }

    @Test
    fun truncatedPayloadIsRejected() {
        val plain = ByteArray(3 * AgePayload.CHUNK_SIZE)
        val cipher = encrypt(plain, "pw")
        // Dropping the final chunk leaves a stream that ends on a non-final chunk.
        val truncated = cipher.copyOf(cipher.size - (AgePayload.CHUNK_SIZE + AgePayload.TAG_SIZE))
        try {
            decrypt(truncated, "pw")
            fail("truncation not detected")
        } catch (e: AgeException) {
            // expected
        }
    }

    /** Official vectors from C2SP/CCTV; see test resources. */
    @Test
    fun cctvVectors() {
        val dir = File(javaClass.classLoader!!.getResource("age-cctv/scrypt")!!.toURI()).parentFile
        val vectors = dir.listFiles()!!.filter { it.name.startsWith("scrypt") }
        assertEquals(25, vectors.size)
        for (vector in vectors) {
            val bytes = vector.readBytes()
            val split = String(bytes, Charsets.ISO_8859_1).indexOf("\n\n")
            val meta = String(bytes, 0, split, Charsets.UTF_8).lines().associate {
                it.substringBefore(": ") to it.substringAfter(": ")
            }
            val file = bytes.copyOfRange(split + 2, bytes.size)
            val expect = meta.getValue("expect")
            val result = runCatching { decrypt(file, meta.getValue("passphrase")) }
            when (expect) {
                "success" -> {
                    val digest = MessageDigest.getInstance("SHA-256").digest(result.getOrThrow())
                    assertEquals(vector.name, meta.getValue("payload"), digest.joinToString("") { "%02x".format(it) })
                }
                "no match" -> assertTrue("${vector.name}: ${result.exceptionOrNull()}", result.exceptionOrNull() is AgeNoMatchException)
                else -> {
                    val error = result.exceptionOrNull()
                    assertTrue("${vector.name} should fail ($expect)", error is AgeException && error !is AgeNoMatchException)
                }
            }
        }
    }
}
