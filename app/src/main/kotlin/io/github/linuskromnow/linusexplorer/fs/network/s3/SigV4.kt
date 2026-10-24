package io.github.linuskromnow.linusexplorer.fs.network.s3

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 for S3 (https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html).
 * Works on already-encoded paths so the signed and the sent request are byte-for-byte the same.
 */
internal class SigV4(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
    private val service: String = "s3",
) {
    /**
     * Returns the headers to add: `x-amz-date`, `x-amz-content-sha256` and `Authorization`.
     * [headers] must contain `host` and every other header that should be signed (lowercase names).
     */
    fun sign(
        method: String,
        encodedPath: String,
        query: List<Pair<String, String>>,
        headers: Map<String, String>,
        payloadSha256: String,
        now: Instant = Instant.now(),
    ): Map<String, String> {
        val amzDate = AMZ_DATE.format(now)
        val dateStamp = amzDate.substring(0, 8)
        val all = (headers + mapOf("x-amz-date" to amzDate, "x-amz-content-sha256" to payloadSha256))
            .mapKeys { it.key.lowercase() }
            .toSortedMap()
        val canonicalHeaders = all.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" }
        val signedHeaders = all.keys.joinToString(";")
        val canonicalRequest = listOf(
            method,
            encodedPath,
            canonicalQuery(query),
            canonicalHeaders,
            signedHeaders,
            payloadSha256,
        ).joinToString("\n")
        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"
        val signingKey = listOf(dateStamp, region, service, "aws4_request")
            .fold("AWS4$secretKey".toByteArray()) { key, part -> hmac(key, part) }
        val signature = hex(hmac(signingKey, stringToSign))
        return mapOf(
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadSha256,
            "Authorization" to "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature",
        )
    }

    companion object {
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        private val AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /** Percent-encodes everything except RFC 3986 unreserved characters, as SigV4 requires. */
        fun encode(value: String, keepSlash: Boolean = false): String {
            val out = StringBuilder()
            for (byte in value.toByteArray()) {
                val c = byte.toInt().toChar()
                val unreserved = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~'
                when {
                    byte >= 0 && unreserved -> out.append(c)
                    keepSlash && c == '/' -> out.append('/')
                    else -> out.append('%').append("%02X".format(byte.toInt() and 0xff))
                }
            }
            return out.toString()
        }

        fun canonicalQuery(query: List<Pair<String, String>>): String =
            query.map { encode(it.first) to encode(it.second) }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .joinToString("&") { "${it.first}=${it.second}" }

        fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

        private fun hmac(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data.toByteArray())
            }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
