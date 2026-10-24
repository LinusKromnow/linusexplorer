package io.github.linuskromnow.linusexplorer.crypto

import org.bouncycastle.crypto.generators.SCrypt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ScryptTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Test vectors from RFC 7914 section 12 (the 1 GiB one is left out to keep tests fast). */
    @Test
    fun rfc7914Vectors() {
        assertEquals(
            "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442" +
                "fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
            hex(Scrypt.derive(ByteArray(0), ByteArray(0), 16, 1, 1, 64)),
        )
        assertEquals(
            "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162" +
                "2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            hex(Scrypt.derive("password".toByteArray(), "NaCl".toByteArray(), 1024, 8, 16, 64)),
        )
        assertEquals(
            "7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2" +
                "d5432955613f0fcf62d49705242a9af9e61e85dc0d651e40dfcf017b45575887",
            hex(Scrypt.derive("pleaseletmein".toByteArray(), "SodiumChloride".toByteArray(), 16384, 8, 1, 64)),
        )
    }

    @Test
    fun matchesBouncyCastle() {
        val password = "correct horse battery staple".toByteArray()
        val salt = ByteArray(32) { it.toByte() }
        for (n in listOf(2, 64, 4096)) {
            assertArrayEquals(SCrypt.generate(password, salt, n, 8, 1, 32), Scrypt.derive(password, salt, n, 8, 1, 32))
        }
    }
}
