package io.github.linuskromnow.linusexplorer.editor

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class TextEncoding(val charset: Charset, val label: String) {
    UTF8(Charsets.UTF_8, "UTF-8"),
    LATIN1(Charsets.ISO_8859_1, "ISO-8859-1"),
}

enum class LineEnding(val sequence: String, val label: String) {
    LF("\n", "LF"),
    CRLF("\r\n", "CRLF"),
    CR("\r", "CR"),
    ;

    companion object {
        /** The most frequent ending in [text]; LF when there is no line break at all. */
        fun detect(text: String): LineEnding {
            var crlf = 0
            var lf = 0
            var cr = 0
            var i = 0
            while (i < text.length) {
                when (text[i]) {
                    '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') {
                        crlf++
                        i++
                    } else {
                        cr++
                    }
                    '\n' -> lf++
                }
                i++
            }
            return when {
                crlf > lf && crlf >= cr -> CRLF
                cr > lf && cr > crlf -> CR
                else -> LF
            }
        }

        /** Converts every line break to "\n", which is what the editor works with internally. */
        fun normalize(text: String): String =
            if ('\r' in text) text.replace("\r\n", "\n").replace('\r', '\n') else text
    }

    /** Converts "\n"-only [text] to this ending. */
    fun apply(text: String): String = if (this == LF) text else text.replace("\n", sequence)
}

/** [text] always uses "\n"; [lineEnding] remembers what to write back. */
data class DecodedText(
    val text: String,
    val encoding: TextEncoding,
    val hasBom: Boolean,
    val lineEnding: LineEnding,
)

/** Detects and converts the encoding of text files: UTF-8 (optionally with BOM), else ISO-8859-1. */
object TextCodec {
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private const val SNIFF_BYTES = 8192

    /** NUL bytes or a high share of control characters in the first few KB mean binary data. */
    fun looksBinary(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val n = minOf(length, SNIFF_BYTES)
        if (n == 0) return false
        var control = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x20 && b != '\t'.code && b != '\n'.code && b != '\r'.code && b != 0x0C && b != 0x08 && b != 0x1B) control++
        }
        return control * 10 > n
    }

    /**
     * Decodes [bytes], or returns null if they look binary. With [truncated] the data is a prefix
     * of a bigger file, so a multi-byte character cut off at the end is not an error.
     */
    fun decode(bytes: ByteArray, truncated: Boolean = false): DecodedText? {
        if (looksBinary(bytes)) return null
        val hasBom = bytes.size >= BOM.size && BOM.indices.all { bytes[it] == BOM[it] }
        val offset = if (hasBom) BOM.size else 0
        val utf8 = decodeUtf8(bytes, offset, partial = truncated)
        val (raw, encoding, bom) = if (utf8 != null) {
            Triple(utf8, TextEncoding.UTF8, hasBom)
        } else {
            Triple(String(bytes, Charsets.ISO_8859_1), TextEncoding.LATIN1, false)
        }
        return DecodedText(LineEnding.normalize(raw), encoding, bom, LineEnding.detect(raw))
    }

    /** True if [text] survives a round trip through [encoding]. */
    fun canEncode(text: String, encoding: TextEncoding): Boolean = encoding.charset.newEncoder().canEncode(text)

    /** Inverse of [decode]; [text] must use "\n" line breaks. */
    fun encode(text: String, encoding: TextEncoding, hasBom: Boolean, lineEnding: LineEnding): ByteArray {
        val body = lineEnding.apply(text).toByteArray(encoding.charset)
        return if (hasBom && encoding == TextEncoding.UTF8) BOM + body else body
    }

    private fun decodeUtf8(bytes: ByteArray, offset: Int, partial: Boolean): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
        val out = CharBuffer.allocate(bytes.size - offset + 1)
        if (decoder.decode(input, out, !partial).isError) return null
        if (!partial && decoder.flush(out).isError) return null
        out.flip()
        return out.toString()
    }
}
