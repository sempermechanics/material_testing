package com.indicvision.semper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The viewer names and pictures a frame from the index in its `.dat` name, not
 * its position in the listing: past a skipped frame the two differ.
 */
class SessionPathsFrameIndexTest {

    @Test
    fun `a frame file names its planned index`() {
        assertEquals(0, SessionPaths.frameIndexOf(SessionPaths.frameDatName(0)))
        assertEquals(7, SessionPaths.frameIndexOf(SessionPaths.frameDatName(7)))
        assertEquals(12345, SessionPaths.frameIndexOf(SessionPaths.frameDatName(12345)))
    }

    @Test
    fun `a listing with a gap keeps each file's own index`() {
        val listing = listOf(0, 1, 3).map(SessionPaths::frameDatName)
        assertEquals(listOf(0, 1, 3), listing.map(SessionPaths::frameIndexOf))
    }

    @Test
    fun `a listing maps each position to its planned frame`() {
        val files = listOf(0, 2, 3).map { File(SessionPaths.frameDatName(it)) } + File("odd.dat")
        assertEquals(listOf(0, 2, 3, 3), SessionPaths.plannedFrameIndices(files))
    }

    @Test
    fun `a planned frame finds its own file past a gap`() {
        // A load or curve point for planned frame 2 must read frame_0002, not the
        // listing's third file; a skipped frame has no file at all (TD-91).
        val files = listOf(0, 2, 3).map { File(SessionPaths.frameDatName(it)) } + File("odd.dat")
        val byFrame = SessionPaths.datByPlannedFrame(files)
        assertEquals(setOf(0, 2, 3), byFrame.keys)
        assertEquals(SessionPaths.frameDatName(2), byFrame.getValue(2).name)
        assertEquals(SessionPaths.frameDatName(3), byFrame.getValue(3).name)
        assertNull(byFrame[1])
    }

    @Test
    fun `other names have no index`() {
        assertNull(SessionPaths.frameIndexOf("ref.png"))
        assertNull(SessionPaths.frameIndexOf("frame_.dat"))
        assertNull(SessionPaths.frameIndexOf("frame_0001.dat.tmp"))
    }
}
