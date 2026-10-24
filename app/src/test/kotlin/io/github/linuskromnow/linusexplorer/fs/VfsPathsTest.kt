package io.github.linuskromnow.linusexplorer.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsPathsTest {
    @Test
    fun normalize() {
        assertEquals("/", VfsPaths.normalize(""))
        assertEquals("/", VfsPaths.normalize("//"))
        assertEquals("/a/b", VfsPaths.normalize("/a//b/"))
        assertEquals("/a/c", VfsPaths.normalize("/a/./b/../c"))
        assertEquals("/", VfsPaths.normalize("/.."))
    }

    @Test
    fun parentAndName() {
        assertNull(VfsPaths.parent("/"))
        assertEquals("/", VfsPaths.parent("/a"))
        assertEquals("/a", VfsPaths.parent("/a/b/"))
        assertEquals("b", VfsPaths.name("/a/b"))
        assertEquals("/", VfsPaths.name("/"))
    }

    @Test
    fun child() {
        assertEquals("/a", VfsPaths.child("/", "a"))
        assertEquals("/a/b", VfsPaths.child("/a", "b"))
        assertEquals("/a/b", VfsPaths.child("/a/", "b"))
    }

    @Test
    fun ancestry() {
        assertTrue(VfsPaths.isAncestorOrSelf("/a", "/a"))
        assertTrue(VfsPaths.isAncestorOrSelf("/a", "/a/b/c"))
        assertTrue(VfsPaths.isAncestorOrSelf("/", "/anything"))
        assertFalse(VfsPaths.isAncestorOrSelf("/a", "/ab"))
        assertFalse(VfsPaths.isAncestorOrSelf("/a/b", "/a"))
    }

    @Test
    fun relativeSegments() {
        assertEquals(listOf("Download", "x"), VfsPaths.relativeSegments("/storage/emulated/0", "/storage/emulated/0/Download/x"))
        assertEquals(listOf("storage"), VfsPaths.relativeSegments("/", "/storage"))
        assertEquals(emptyList<String>(), VfsPaths.relativeSegments("/a", "/a"))
    }

    @Test
    fun validNames() {
        assertTrue(VfsPaths.isValidName("photo.jpg"))
        assertTrue(VfsPaths.isValidName(".nomedia"))
        assertFalse(VfsPaths.isValidName(""))
        assertFalse(VfsPaths.isValidName(".."))
        assertFalse(VfsPaths.isValidName("a/b"))
    }
}
