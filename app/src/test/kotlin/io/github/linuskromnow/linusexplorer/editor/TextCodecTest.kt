package io.github.linuskromnow.linusexplorer.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCodecTest {
    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    @Test
    fun decodesPlainUtf8() {
        val decoded = TextCodec.decode("héllo\nwörld".toByteArray())!!
        assertEquals("héllo\nwörld", decoded.text)
        assertEquals(TextEncoding.UTF8, decoded.encoding)
        assertFalse(decoded.hasBom)
        assertEquals(LineEnding.LF, decoded.lineEnding)
    }

    @Test
    fun bomIsStrippedAndPreserved() {
        val bytes = bom + "abc".toByteArray()
        val decoded = TextCodec.decode(bytes)!!
        assertEquals("abc", decoded.text)
        assertTrue(decoded.hasBom)
        assertArrayEquals(bytes, TextCodec.encode(decoded.text, decoded.encoding, decoded.hasBom, decoded.lineEnding))
    }

    @Test
    fun fallsBackToLatin1() {
        val bytes = byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())
        val decoded = TextCodec.decode(bytes)!!
        assertEquals("café", decoded.text)
        assertEquals(TextEncoding.LATIN1, decoded.encoding)
        assertArrayEquals(bytes, TextCodec.encode(decoded.text, decoded.encoding, decoded.hasBom, decoded.lineEnding))
    }

    @Test
    fun latin1CannotEncodeEuroSign() {
        assertTrue(TextCodec.canEncode("café", TextEncoding.LATIN1))
        assertFalse(TextCodec.canEncode("5 €", TextEncoding.LATIN1))
        assertTrue(TextCodec.canEncode("5 €", TextEncoding.UTF8))
    }

    @Test
    fun truncatedUtf8DoesNotFallBackToLatin1() {
        val full = "aé".toByteArray() // 'é' is two bytes
        val decoded = TextCodec.decode(full.copyOf(full.size - 1), truncated = true)!!
        assertEquals(TextEncoding.UTF8, decoded.encoding)
        assertEquals("a", decoded.text)
    }

    @Test
    fun detectsBinary() {
        assertNull(TextCodec.decode(byteArrayOf(1, 2, 0, 3, 4)))
        assertNull(TextCodec.decode(ByteArray(100) { 2 }))
        assertNotNull(TextCodec.decode(ByteArray(0)))
    }

    @Test
    fun preservesCrlf() {
        val bytes = "one\r\ntwo\r\nthree".toByteArray()
        val decoded = TextCodec.decode(bytes)!!
        assertEquals(LineEnding.CRLF, decoded.lineEnding)
        assertEquals("one\ntwo\nthree", decoded.text)
        assertArrayEquals(bytes, TextCodec.encode(decoded.text, decoded.encoding, decoded.hasBom, decoded.lineEnding))
    }

    @Test
    fun preservesCr() {
        val bytes = "one\rtwo\r".toByteArray()
        val decoded = TextCodec.decode(bytes)!!
        assertEquals(LineEnding.CR, decoded.lineEnding)
        assertArrayEquals(bytes, TextCodec.encode(decoded.text, decoded.encoding, decoded.hasBom, decoded.lineEnding))
    }

    @Test
    fun mixedEndingsUseTheMajority() {
        assertEquals(LineEnding.CRLF, LineEnding.detect("a\r\nb\r\nc\n"))
        assertEquals(LineEnding.LF, LineEnding.detect("a\nb\nc\r\n"))
        assertEquals(LineEnding.LF, LineEnding.detect("no breaks"))
        assertEquals("a\nb\nc\nd", LineEnding.normalize("a\r\nb\rc\nd"))
    }
}
