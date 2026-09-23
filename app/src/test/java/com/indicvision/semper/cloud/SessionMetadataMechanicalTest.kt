@file:Suppress("MagicNumber")

package com.indicvision.semper.cloud

import com.indicvision.semper.data.BeamEdgeTaps
import com.indicvision.semper.data.CloudRestore
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SessionUploadMetadata
import com.indicvision.semper.data.SpecimenGeometry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `metadata.json` is the only place a cloud backup keeps the test type and the
 * machine loads, so these pin both halves: what an upload writes for a typed
 * and an untyped session, and what a restore reads back from `/3`, `/4`,
 * `/5` (which adds the bending geometry) and `/6` (its load-point taps).
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric supplies org.json; sdk pinned like every other Robolectric test.
@Config(sdk = [34])
class SessionMetadataMechanicalTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var targets = 0

    @Test
    fun `schema is 6 and still counts as a split layout`() {
        assertEquals("indic.session.metadata/6", SessionUploadMetadata.SCHEMA)
        assertEquals(4, SessionUploadMetadata.SCHEMA_MECHANICAL_TEST)
        assertEquals(5, SessionUploadMetadata.SCHEMA_SPECIMEN_GEOMETRY)
        assertEquals(6, SessionUploadMetadata.SCHEMA_LOAD_POINT)
        assertTrue(CloudRestore.isSplitLayout(SessionUploadMetadata.SCHEMA))
    }

    @Test
    fun `a tensile session writes no geometry object, as a schema-4 file would`() {
        val test = SessionUploadMetadata.testJson(record(testType = "tensile", loadsN = listOf(0f, 1f, 2f)))!!

        assertFalse(test.has("geometry"))
    }

    @Test
    fun `bending geometry round-trips through metadata and restore, entered dimensions only`() {
        val geometry = SpecimenGeometry(spanMm = 80f, widthMm = 10.5f, thicknessMm = 4f)
        val record = record(testType = "bending", loadsN = listOf(0f, 100f, 200f)).copy(geometry = geometry)

        val test = SessionUploadMetadata.testJson(record)!!
        val json = test.getJSONObject("geometry")
        assertEquals(setOf("spanMm", "widthMm", "thicknessMm"), json.keys().asSequence().toSet())
        val meta = JSONObject()
            .put("schema", SessionUploadMetadata.SCHEMA)
            .put("test", test)
            .put("frames", SessionUploadMetadata.framesJson(record))
        val restored = CloudRestore.recordFrom(meta, target())

        assertEquals("bending", restored.testType)
        assertEquals(geometry, restored.geometry)
        assertEquals(listOf(0f, 100f, 200f), restored.loadsN)
    }

    @Test
    fun `load-point taps round-trip and a schema-5 geometry restores with none`() {
        val taps = BeamEdgeTaps(topX = 512f, topY = 300.5f, bottomX = 514f, bottomY = 428f)
        val geometry = SpecimenGeometry(spanMm = 935f, widthMm = 150f, thicknessMm = 6.38f, loadPoint = taps)
        val record = record(testType = "bending", loadsN = listOf(0f, 42f, 51f)).copy(geometry = geometry)

        val test = SessionUploadMetadata.testJson(record)!!
        assertTrue(test.getJSONObject("geometry").has("loadPoint"))
        val restored = CloudRestore.recordFrom(JSONObject().put("test", test), target())
        assertEquals(geometry, restored.geometry)

        test.getJSONObject("geometry").remove("loadPoint")
        val older = CloudRestore.recordFrom(JSONObject().put("test", test), target())
        assertEquals(BeamEdgeTaps.NONE, older.geometry.loadPoint)
        assertEquals(935f, older.geometry.spanMm)
    }

    @Test
    fun `a half-entered geometry round-trips and a schema-4 test object restores as no geometry`() {
        val geometry = SpecimenGeometry(spanMm = 50f)
        val record = record(testType = "bending", loadsN = listOf(0f, 10f, 20f)).copy(geometry = geometry)
        val test = SessionUploadMetadata.testJson(record)!!
        assertEquals(setOf("spanMm"), test.getJSONObject("geometry").keys().asSequence().toSet())

        val restored = CloudRestore.recordFrom(JSONObject().put("test", test), target())
        assertEquals(geometry, restored.geometry)

        test.remove("geometry")
        val older = CloudRestore.recordFrom(JSONObject().put("test", test), target())
        assertEquals("bending", older.testType)
        assertEquals(SpecimenGeometry.NONE, older.geometry)
    }

    @Test
    fun `an untyped session writes no test object and no per-frame load`() {
        val record = record(testType = "", loadsN = emptyList())

        assertNull(SessionUploadMetadata.testJson(record))
        val frames = SessionUploadMetadata.framesJson(record)
        for (i in 0 until frames.length()) {
            assertFalse(frames.getJSONObject(i).has("loadN"))
        }
    }

    @Test
    fun `a typed session with loads round-trips through metadata and restore`() {
        val record = record(testType = "tensile", loadsN = listOf(0f, 850.25f, 1700.5f))

        val meta = JSONObject()
            .put("schema", SessionUploadMetadata.SCHEMA)
            .put("test", SessionUploadMetadata.testJson(record))
            .put("frames", SessionUploadMetadata.framesJson(record))
        val restored = CloudRestore.recordFrom(meta, target())

        assertEquals("tensile", restored.testType)
        assertEquals(12.5f, restored.crossSectionMm2, 1e-4f)
        assertTrue(restored.loadAxisX)
        assertEquals(listOf(0f, 850.25f, 1700.5f), restored.loadsN)
        assertEquals("utm.csv", restored.loadSource)
        assertEquals("RESAMPLED", restored.loadMapping)
        assertTrue(restored.hasMachineLoads)
    }

    @Test
    fun `load axis y survives the round-trip`() {
        val record = record(testType = "compression", loadsN = listOf(0f, -10f, -20f), loadAxisX = false)
        val meta = JSONObject()
            .put("test", SessionUploadMetadata.testJson(record))
            .put("frames", SessionUploadMetadata.framesJson(record))

        val restored = CloudRestore.recordFrom(meta, target())

        assertFalse(restored.loadAxisX)
        assertEquals(listOf(0f, -10f, -20f), restored.loadsN)
    }

    @Test
    fun `a schema-3 backup restores with no test type and no loads`() {
        val meta = JSONObject()
            .put("schema", "indic.session.metadata/3")
            .put("frames", SessionUploadMetadata.framesJson(record(testType = "", loadsN = emptyList())))

        val restored = CloudRestore.recordFrom(meta, target())

        assertEquals("", restored.testType)
        assertEquals(0f, restored.crossSectionMm2, 0f)
        assertTrue(restored.loadAxisX)
        assertTrue(restored.loadsN.isEmpty())
        assertFalse(restored.hasMachineLoads)
    }

    @Test
    fun `a frame without a load drops every load rather than misaligning them`() {
        val record = record(testType = "tensile", loadsN = listOf(0f, 850.25f, 1700.5f))
        val frames = SessionUploadMetadata.framesJson(record)
        frames.getJSONObject(1).remove("loadN")
        val meta = JSONObject()
            .put("test", SessionUploadMetadata.testJson(record))
            .put("frames", frames)

        val restored = CloudRestore.recordFrom(meta, target())

        assertEquals("tensile", restored.testType)
        assertTrue(restored.loadsN.isEmpty())
        assertFalse(restored.hasMachineLoads)
    }

    private fun target(): CloudRestore.RestoreRecordTarget {
        // One folder per call: a test that restores twice must not collide.
        val dir = temp.newFolder("sess-${targets++}")
        return CloudRestore.RestoreRecordTarget(
            localId = "local-1",
            cloudSessionId = "cloud-1",
            sessionDir = dir,
            refPath = File(dir, "ref.png").absolutePath,
            existing = null,
        )
    }

    private fun record(
        testType: String,
        loadsN: List<Float>,
        loadAxisX: Boolean = true,
    ) = SessionRecord(
        id = "sess-1",
        name = "Run",
        createdAt = 1750000000000L,
        updatedAt = 1750000000000L,
        frameCount = 3,
        subset = 41,
        step = 5,
        strainWindow = 15,
        imgW = 640,
        imgH = 480,
        roiX = 0,
        roiY = 0,
        roiW = 640,
        roiH = 480,
        refPath = "/data/sessions/sess-1/ref.png",
        refName = "ref.png",
        sessionDir = "/data/sessions/sess-1",
        defNames = listOf("d1.png", "d2.png", "d3.png"),
        testType = testType,
        crossSectionMm2 = if (testType.isBlank()) 0f else 12.5f,
        loadAxisX = loadAxisX,
        loadsN = loadsN,
        loadSource = if (loadsN.isEmpty()) "" else "utm.csv",
        loadMapping = if (loadsN.isEmpty()) "" else "RESAMPLED",
    )
}
