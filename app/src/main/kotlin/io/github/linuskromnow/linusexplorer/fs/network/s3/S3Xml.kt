package io.github.linuskromnow.linusexplorer.fs.network.s3

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.Instant
import javax.xml.parsers.SAXParserFactory

internal data class S3Object(val key: String, val size: Long, val lastModified: Long)

/** One page of ListObjectsV2. */
internal data class S3Listing(
    val objects: List<S3Object>,
    val prefixes: List<String>,
    val nextToken: String?,
)

/** An S3 error document: `<Error><Code>…</Code><Message>…</Message></Error>`. */
internal data class S3Error(val code: String, val message: String, val region: String?)

/** Tiny SAX helpers for the few S3 XML documents we read. */
internal object S3Xml {
    fun listing(input: InputStream): S3Listing {
        val objects = mutableListOf<S3Object>()
        val prefixes = mutableListOf<String>()
        var truncated = false
        var token: String? = null
        var key = ""
        var size = 0L
        var modified = 0L
        parse(input) { path, value ->
            when (path) {
                "ListBucketResult/Contents/Key" -> key = value
                "ListBucketResult/Contents/Size" -> size = value.toLongOrNull() ?: 0
                "ListBucketResult/Contents/LastModified" -> modified = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)
                "ListBucketResult/Contents" -> objects += S3Object(key, size, modified)
                "ListBucketResult/CommonPrefixes/Prefix" -> prefixes += value
                "ListBucketResult/IsTruncated" -> truncated = value == "true"
                "ListBucketResult/NextContinuationToken" -> token = value
            }
        }
        return S3Listing(objects, prefixes, token.takeIf { truncated })
    }

    fun uploadId(input: InputStream): String {
        var id = ""
        parse(input) { path, value -> if (path == "InitiateMultipartUploadResult/UploadId") id = value }
        return id
    }

    /** Null if the body isn't an S3 error document (S3 sometimes reports errors inside a 200). */
    fun error(input: InputStream): S3Error? {
        var code: String? = null
        var message = ""
        var region: String? = null
        runCatching {
            parse(input) { path, value ->
                when (path) {
                    "Error/Code" -> code = value
                    "Error/Message" -> message = value
                    "Error/Region" -> region = value
                }
            }
        }
        return code?.let { S3Error(it, message, region) }
    }

    /** Calls [onEnd] with the slash-joined element path (namespaces ignored) and its text, for every element. */
    private fun parse(input: InputStream, onEnd: (String, String) -> Unit) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.newSAXParser().parse(
            input,
            object : DefaultHandler() {
                private val path = ArrayDeque<String>()
                private val text = StringBuilder()

                override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                    path.addLast(localName.ifEmpty { qName })
                    text.setLength(0)
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    text.append(ch, start, length)
                }

                override fun endElement(uri: String, localName: String, qName: String) {
                    onEnd(path.joinToString("/"), text.toString().trim())
                    path.removeLastOrNull()
                    text.setLength(0)
                }
            },
        )
    }
}
