package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.network.s3.SigV4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Examples from the AWS S3 SigV4 documentation ("Examples: Signature Calculations"). */
class SigV4Test {
    private val signer = SigV4("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1")
    private val time = Instant.parse("2013-05-24T00:00:00Z")

    private fun signature(headers: Map<String, String>) = headers.getValue("Authorization").substringAfter("Signature=")

    @Test
    fun getObject() {
        val headers = signer.sign(
            method = "GET",
            encodedPath = "/test.txt",
            query = emptyList(),
            headers = mapOf("host" to "examplebucket.s3.amazonaws.com", "range" to "bytes=0-9"),
            payloadSha256 = SigV4.EMPTY_SHA256,
            now = time,
        )
        assertEquals("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", signature(headers))
        assertTrue(headers.getValue("Authorization").contains("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date"))
    }

    @Test
    fun listObjects() {
        val headers = signer.sign(
            method = "GET",
            encodedPath = "/",
            query = listOf("max-keys" to "2", "prefix" to "J"),
            headers = mapOf("host" to "examplebucket.s3.amazonaws.com"),
            payloadSha256 = SigV4.EMPTY_SHA256,
            now = time,
        )
        assertEquals("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7", signature(headers))
    }

    @Test
    fun encoding() {
        assertEquals("a%20b%2Bc~d.e_f-g", SigV4.encode("a b+c~d.e_f-g"))
        assertEquals("Fotos/%C3%84%20b.jpg", SigV4.encode("Fotos/Ä b.jpg", keepSlash = true))
        assertEquals("a=1&b=2&prefix=x%2Fy", SigV4.canonicalQuery(listOf("prefix" to "x/y", "b" to "2", "a" to "1")))
    }
}
