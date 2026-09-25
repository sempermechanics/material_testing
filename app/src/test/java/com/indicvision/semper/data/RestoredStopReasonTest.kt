package com.indicvision.semper.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A run that stopped short must still say so after a backup and restore; it
 * used to come back as a clean run of however many frames it reached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RestoredStopReasonTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun record(stopCode: Int, planned: Int) = SessionRecord(
        id = "s_test", name = "test", createdAt = 0, updatedAt = 0, frameCount = 2,
        subset = 21, step = 5, strainWindow = 15,
        imgW = 64, imgH = 64, roiX = 0, roiY = 0, roiW = 64, roiH = 64,
        refPath = "", refName = "ref.png", sessionDir = "",
        defNames = listOf("a.png", "b.png"),
        stopCode = stopCode, plannedFrameCount = planned,
    )

    private fun roundTrip(record: SessionRecord): SessionRecord {
        val meta = JSONObject(SessionUploadMetadata.buildMetadataJson(record, context))
        val target = CloudRestore.RestoreRecordTarget(
            localId = "s_restored",
            cloudSessionId = "c1",
            sessionDir = File("restored"),
            refPath = "",
            existing = null,
        )
        return CloudRestore.recordFrom(meta, target)
    }

    @Test
    fun `a run that stopped early comes back stopped early`() {
        val restored = roundTrip(record(stopCode = -3, planned = 50))
        assertTrue(restored.stoppedEarly)
        assertEquals(-3, restored.stopCode)
        assertEquals(50, restored.plannedFrameCount)
    }

    @Test
    fun `a backup made before the fields existed restores as a completed run`() {
        val meta = JSONObject(SessionUploadMetadata.buildMetadataJson(record(0, 0), context))
        meta.getJSONObject("metrics").apply {
            remove("stopCode")
            remove("plannedFrameCount")
        }
        val target = CloudRestore.RestoreRecordTarget("s_r", "c1", File("r"), "", null)
        val restored = CloudRestore.recordFrom(meta, target)
        assertFalse(restored.stoppedEarly)
        assertEquals(0, restored.plannedFrameCount)
    }
}
