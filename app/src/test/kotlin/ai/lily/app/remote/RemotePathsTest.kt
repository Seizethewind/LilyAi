package ai.lily.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemotePathsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun ordinaryNamesAreSafe() {
        assertTrue(RemotePaths.isSafeName("index.html"))
        assertTrue(RemotePaths.isSafeName(".htaccess"))
        assertTrue(RemotePaths.isSafeName("my file (1).txt"))
    }

    @Test
    fun dotsSlashesAndControlCharactersAreNot() {
        for (bad in listOf("", ".", "..", "a/b", "a\\b", "a\u0000b", "a\nb")) {
            assertFalse("'$bad' should be unsafe", RemotePaths.isSafeName(bad))
        }
    }

    @Test
    fun aPathNeedsEverySegmentSafe() {
        assertTrue(RemotePaths.isSafePath("a/b/c.txt"))
        assertFalse(RemotePaths.isSafePath("../x"))
        assertFalse(RemotePaths.isSafePath("a//b"))
        assertFalse(RemotePaths.isSafePath("/abs"))
        assertFalse(RemotePaths.isSafePath(""))
    }

    @Test
    fun childParentAndName() {
        assertEquals("a", RemotePaths.child("", "a"))
        assertEquals("a/b", RemotePaths.child("a", "b"))
        assertEquals("a/b", RemotePaths.parent("a/b/c.txt"))
        assertEquals("", RemotePaths.parent("c.txt"))
        assertEquals("c.txt", RemotePaths.nameOf("a/b/c.txt"))
        assertEquals("c.txt", RemotePaths.nameOf("c.txt"))
    }

    @Test
    fun localPathsStayInsideTheRoot() {
        val root = tmp.newFolder("ws")
        assertNotNull(RemotePaths.resolveLocal(root, "a/b.txt"))
        assertNull(RemotePaths.resolveLocal(root, "../outside.txt"))
        assertNull(RemotePaths.resolveLocal(root, "a/../../outside.txt"))
        assertEquals(File(root.canonicalFile, "a/b.txt"), RemotePaths.resolveLocal(root, "a/b.txt"))
    }

    @Test
    fun hashesAreStableAndMatchForBytesAndFiles() {
        val file = tmp.newFile("f.txt")
        file.writeText("hello")
        val expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertEquals(expected, Hashing.sha256Hex("hello".toByteArray()))
        assertEquals(expected, Hashing.sha256Hex(file))
    }

    @Test
    fun atomicWriteReplacesTheFileAndLeavesNoTempFile() {
        val target = File(tmp.root, "sub/x.txt")
        writeAtomically(target, "one".toByteArray())
        writeAtomically(target, "two".toByteArray())
        assertEquals("two", target.readText())
        assertEquals(listOf("x.txt"), target.parentFile.list()!!.toList())
    }
}
