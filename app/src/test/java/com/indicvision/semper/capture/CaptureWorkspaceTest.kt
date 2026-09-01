package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.CaptureWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureWorkspaceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun write(name: String) = folder.newFile(name).also { it.writeText("x") }

    @Test
    fun `removes frames left by a previous run`() {
        write("frame_0000.png")
        write("frame_0074.png")
        write("frame_0110.png")
        val cleared = CaptureWorkspace.clearPreviousRun(folder.root)
        assertEquals(3, cleared)
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test
    fun `keeps the test shot, which the run still needs`() {
        val test = write("test.jpg")
        write("frame_0000.png")
        CaptureWorkspace.clearPreviousRun(folder.root)
        assertTrue(test.exists())
    }

    @Test
    fun `removes a stale reference and warm-up frame too`() {
        write("reference.png")
        write("warmup.png")
        assertEquals(2, CaptureWorkspace.clearPreviousRun(folder.root))
    }

    @Test
    fun `leaves unrelated files alone`() {
        val other = write("session_notes.txt")
        val roi = write("roi_preview.png")
        CaptureWorkspace.clearPreviousRun(folder.root)
        assertTrue(other.exists())
        assertTrue(roi.exists())
    }

    @Test
    fun `an empty or missing directory is not an error`() {
        assertEquals(0, CaptureWorkspace.clearPreviousRun(folder.root))
        assertEquals(0, CaptureWorkspace.clearPreviousRun(folder.root.resolve("gone")))
    }

    @Test
    fun `only exact frame names count as ours`() {
        val backup = write("frame_0001.png.bak")
        val lookalike = write("myframe_0001.png")
        write("frame_0001.png")

        assertEquals(1, CaptureWorkspace.clearPreviousRun(folder.root))
        assertTrue(backup.exists())
        assertTrue(lookalike.exists())
    }

    @Test
    fun `frame names are the ones the sweep deletes`() {
        val frame = write(CaptureWorkspace.frameName(7))

        assertEquals("frame_0007.png", frame.name)
        assertEquals(1, CaptureWorkspace.clearPreviousRun(folder.root))
        assertFalse(frame.exists())
    }
}
