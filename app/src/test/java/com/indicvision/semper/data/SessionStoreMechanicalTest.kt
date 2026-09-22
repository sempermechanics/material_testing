package com.indicvision.semper.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The mechanical-test fields on [SessionRecord] must be invisible to every
 * index written before they existed, and must round-trip once written.
 */
@RunWith(RobolectricTestRunner::class)
// Pinned like every other Robolectric test here: Robolectric ships no
// android-all jar for targetSdk 36.
@Config(sdk = [34])
class SessionStoreMechanicalTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "sessions").deleteRecursively()
    }

    @Test
    fun `a session index written before test types loads with no type and no loads`() {
        val dir = File(context.filesDir, "sessions").apply { mkdirs() }
        File(dir, "index.json").writeText(PRE_TEST_TYPE_INDEX)

        val record = SessionStore.get(context, "sess-untyped")

        assertNotNull(record)
        record!!
        assertEquals("", record.testType)
        assertEquals(0f, record.crossSectionMm2, 0f)
        assertTrue(record.loadAxisX)
        assertEquals(SpecimenGeometry.NONE, record.geometry)
        assertTrue(record.loadsN.isEmpty())
        assertFalse(record.hasMachineLoads)
    }

    @Test
    fun `a bending record keeps its geometry through the store`() {
        val geometry = SpecimenGeometry(spanMm = 80f, widthMm = 10.5f, thicknessMm = 4f)
        val record = typedRecord(loadsN = listOf(0f, 100f, 200f)).copy(testType = "bending", geometry = geometry)

        assertTrue(SessionStore.upsert(context, record))
        val back = SessionStore.get(context, record.id)

        assertNotNull(back)
        assertEquals(geometry, back!!.geometry)
    }

    @Test
    fun `a typed record with per-frame loads round-trips through the store`() {
        val record = typedRecord(loadsN = listOf(0f, 512.5f, -1024f))

        assertTrue(SessionStore.upsert(context, record))
        val back = SessionStore.get(context, record.id)

        assertNotNull(back)
        back!!
        assertEquals("compression", back.testType)
        assertEquals(78.54f, back.crossSectionMm2, 1e-4f)
        assertFalse(back.loadAxisX)
        assertEquals(listOf(0f, 512.5f, -1024f), back.loadsN)
        assertEquals("utm_export.csv", back.loadSource)
        assertEquals("ONE_TO_ONE", back.loadMapping)
        assertTrue(back.hasMachineLoads)
    }

    @Test
    fun `loads that do not cover every frame do not count as machine loads`() {
        val record = typedRecord(loadsN = listOf(0f, 512.5f))

        assertFalse(record.hasMachineLoads)
    }

    private fun typedRecord(loadsN: List<Float>) = SessionRecord(
        id = "sess-typed",
        name = "Compression run",
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
        refPath = "/data/sessions/sess-typed/ref.png",
        refName = "ref.png",
        sessionDir = "/data/sessions/sess-typed",
        defNames = listOf("d1.png", "d2.png", "d3.png"),
        testType = "compression",
        crossSectionMm2 = 78.54f,
        loadAxisX = false,
        loadsN = loadsN,
        loadSource = "utm_export.csv",
        loadMapping = "ONE_TO_ONE",
    )

    private companion object {
        val PRE_TEST_TYPE_INDEX = """
            [
              {
                "id": "sess-untyped",
                "name": "Older run",
                "createdAt": 1750000000000,
                "updatedAt": 1750000000000,
                "frameCount": 2,
                "subset": 41,
                "step": 5,
                "strainWindow": 15,
                "imgW": 640,
                "imgH": 480,
                "roiX": 0,
                "roiY": 0,
                "roiW": 640,
                "roiH": 480,
                "refPath": "/data/sessions/sess-untyped/ref.png",
                "refName": "ref.png",
                "sessionDir": "/data/sessions/sess-untyped",
                "defNames": ["d1.png", "d2.png"]
              }
            ]
        """.trimIndent()
    }
}
