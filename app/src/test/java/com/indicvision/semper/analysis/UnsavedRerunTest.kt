package com.indicvision.semper.analysis

import com.indicvision.semper.data.SessionRecord.SyncState
import com.indicvision.semper.fixtures.sessionRecord
import com.indicvision.semper.ui.analysis.afterUnsavedRerun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A re-run deletes the previous frames before it starts. When it then saves
 * nothing, the Home row must stop describing frames that are gone.
 */
class UnsavedRerunTest {

    private fun previous(state: SyncState) = sessionRecord(
        id = "s1",
        name = "run",
        createdAt = 0,
        frameCount = 12,
        subset = 21,
        imgW = 64,
        imgH = 64,
        syncState = state,
    ).copy(headline = "εxx 1.2 mε", engineStats = listOf(1f, 2f))

    @Test
    fun `nothing on disk and backed up keeps the row, which now opens the cloud copy`() {
        val synced = previous(SyncState.SYNCED)
        assertSame(synced, afterUnsavedRerun(synced, framesOnDisk = 0, stopCode = -2, plannedFrames = 12))
    }

    @Test
    fun `nothing on disk and nothing in the cloud drops the row`() {
        assertNull(afterUnsavedRerun(previous(SyncState.PENDING), 0, -2, 12))
        assertNull(afterUnsavedRerun(previous(SyncState.LOCAL_ONLY), 0, -2, 12))
    }

    @Test
    fun `frames on disk are what the row describes, without the gone run's numbers`() {
        val after = afterUnsavedRerun(previous(SyncState.SYNCED), framesOnDisk = 3, stopCode = -2, plannedFrames = 12)!!
        assertEquals(3, after.frameCount)
        assertEquals(-2, after.stopCode)
        assertEquals(12, after.plannedFrameCount)
        assertEquals("", after.headline)
        assertEquals(emptyList<Float>(), after.engineStats)
        assertEquals(SyncState.LOCAL_ONLY, after.syncState)
    }
}
