package io.github.linuskromnow.linusexplorer.editor

import io.github.linuskromnow.linusexplorer.fs.InMemoryFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFilesTest {
    private val fs = InMemoryFileSystem()
    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private suspend fun writeBytes(path: String, bytes: ByteArray) = fs.openOutput(path).use { it.write(bytes) }

    private suspend fun readBytes(path: String) = fs.openInput(path).use { it.readBytes() }

    private suspend fun loadText(path: String): TextDocument = (TextFiles.load(fs, path) as LoadResult.Text).document

    @Test
    fun roundTripThroughVfs() = runTest {
        fs.write("/notes/a.txt", "hello\nwörld\n")
        val doc = loadText("/notes/a.txt")
        assertEquals("hello\nwörld\n", doc.text)
        TextFiles.save(fs, "/notes/a.txt", doc.text + "more\n", doc)
        assertEquals("hello\nwörld\nmore\n", fs.read("/notes/a.txt"))
    }

    @Test
    fun saveLeavesNoTempFilesBehind() = runTest {
        fs.write("/d/a.txt", "x")
        val doc = loadText("/d/a.txt")
        TextFiles.save(fs, "/d/a.txt", "y", doc)
        assertEquals(setOf("/", "/d", "/d/a.txt"), fs.allPaths())
        assertEquals("y", fs.read("/d/a.txt"))
    }

    @Test
    fun saveKeepsBomAndCrlf() = runTest {
        writeBytes("/a.txt", bom + "a\r\nb\r\n".toByteArray())
        val doc = loadText("/a.txt")
        assertTrue(doc.hasBom)
        assertEquals(LineEnding.CRLF, doc.lineEnding)
        TextFiles.save(fs, "/a.txt", "a\nb\nc\n", doc)
        assertArrayEquals(bom + "a\r\nb\r\nc\r\n".toByteArray(), readBytes("/a.txt"))
    }

    @Test
    fun latin1FileStaysLatin1() = runTest {
        writeBytes("/l.txt", byteArrayOf('c'.code.toByte(), 0xE9.toByte()))
        val doc = loadText("/l.txt")
        assertEquals(TextEncoding.LATIN1, doc.encoding)
        val saved = TextFiles.save(fs, "/l.txt", "café!", doc)
        assertEquals(TextEncoding.LATIN1, saved.encoding)
        val expected = byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte(), '!'.code.toByte())
        assertArrayEquals(expected, readBytes("/l.txt"))
    }

    @Test
    fun latin1FileUpgradesToUtf8WhenNeeded() = runTest {
        writeBytes("/l.txt", byteArrayOf('c'.code.toByte(), 0xE9.toByte()))
        val doc = loadText("/l.txt")
        val saved = TextFiles.save(fs, "/l.txt", "cé €", doc)
        assertEquals(TextEncoding.UTF8, saved.encoding)
        assertEquals("cé €", fs.read("/l.txt"))
    }

    @Test
    fun binaryFilesAreRefused() = runTest {
        writeBytes("/b.bin", byteArrayOf(0x7F, 0x45, 0, 0, 1, 2))
        assertEquals(LoadResult.Binary, TextFiles.load(fs, "/b.bin"))
        assertFalse(TextFiles.looksLikeText(fs, "/b.bin"))
    }

    @Test
    fun sniffAcceptsText() = runTest {
        fs.write("/Makefile", "all:\n\techo hi\n")
        assertTrue(TextFiles.looksLikeText(fs, "/Makefile"))
    }

    @Test
    fun hugeFilesAreTruncatedAndReadOnly() = runTest {
        writeBytes("/big.txt", ByteArray(TextFiles.MAX_EDITABLE_BYTES + 100) { 'a'.code.toByte() })
        val doc = loadText("/big.txt")
        assertTrue(doc.truncated)
        assertEquals(TextFiles.MAX_EDITABLE_BYTES, doc.text.length)
        val failed = runCatching { TextFiles.save(fs, "/big.txt", "x", doc) }
        assertTrue(failed.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun fileAtTheLimitIsEditable() = runTest {
        writeBytes("/ok.txt", ByteArray(TextFiles.MAX_EDITABLE_BYTES) { 'a'.code.toByte() })
        assertFalse(loadText("/ok.txt").truncated)
    }
}
