package io.github.linuskromnow.linusexplorer.fs.network.webdav

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory

/** One `<d:response>` of a PROPFIND multistatus reply. */
internal data class DavResource(
    /** The raw (percent-encoded) href; resolve it against the request URL. */
    val href: String,
    val isCollection: Boolean,
    val size: Long,
    val lastModified: Long,
)

/** Body asking only for the properties a file list needs. */
internal const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

/** Parses a WebDAV multistatus document with the platform SAX parser (available on Android and the JVM). */
internal object PropfindParser {
    private const val DAV = "DAV:"

    fun parse(input: InputStream): List<DavResource> {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        // No external entities: the reply comes from a server we don't control.
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val handler = Handler()
        factory.newSAXParser().parse(input, handler)
        return handler.resources
    }

    private class Handler : DefaultHandler() {
        val resources = mutableListOf<DavResource>()
        private val text = StringBuilder()

        private var href: String? = null
        private var collection = false
        private var size = 0L
        private var modified = 0L

        // Properties of the current <propstat>, kept only if its status is 200.
        private var propCollection = false
        private var propSize: Long? = null
        private var propModified: Long? = null
        private var propOk = false

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            text.setLength(0)
            if (uri != DAV) return
            when (localName) {
                "response" -> {
                    href = null
                    collection = false
                    size = 0
                    modified = 0
                }
                "propstat" -> {
                    propCollection = false
                    propSize = null
                    propModified = null
                    propOk = false
                }
                "collection" -> propCollection = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            if (uri != DAV) return
            val value = text.toString().trim()
            when (localName) {
                "href" -> if (href == null) href = value
                "status" -> propOk = value.contains(" 200 ")
                "getcontentlength" -> propSize = value.toLongOrNull()
                "getlastmodified" -> propModified = parseDate(value)
                "propstat" -> if (propOk) {
                    if (propCollection) collection = true
                    propSize?.let { size = it }
                    propModified?.let { modified = it }
                }
                "response" -> href?.let { resources += DavResource(it, collection, size, modified) }
            }
        }

        private fun parseDate(value: String): Long? =
            runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }
}
