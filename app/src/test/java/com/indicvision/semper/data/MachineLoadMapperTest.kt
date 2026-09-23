@file:Suppress("MagicNumber")

package com.indicvision.semper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineLoadMapperTest {

    private fun parsed(loads: List<Float>, times: List<Float>? = null) = ParsedLoadCsv(
        loadsN = loads,
        timesS = times,
        unit = LoadUnit.N,
        loadColumn = 0,
        timeColumn = if (times == null) null else 1,
        loadHeader = "Load (N)",
        warnings = emptyList(),
    )

    private fun map(
        loads: List<Float>,
        frames: Int,
        times: List<Float>? = null,
        frameTimesMs: List<Long> = emptyList(),
        testType: TestType = TestType.TENSILE,
    ) = MachineLoadMapper.map(parsed(loads, times), frames, frameTimesMs, testType)

    @Test
    fun `one row per frame maps one to one`() {
        val table = map(listOf(10f, 20f, 30f), frames = 3)!!

        assertEquals(listOf(10f, 20f, 30f), table.loadsN)
        assertEquals(LoadMapping.ONE_TO_ONE, table.mapping)
        assertTrue(table.warnings.isEmpty())
        assertEquals(3, table.sourceRows)
    }

    @Test
    fun `one extra leading row that is the unloaded reference is dropped`() {
        val table = map(listOf(0f, 10f, 20f, 30f), frames = 3)!!

        assertEquals(listOf(10f, 20f, 30f), table.loadsN)
        assertEquals(LoadMapping.ONE_TO_ONE_DROP_FIRST, table.mapping)
        assertEquals(listOf(LoadMapWarning.FIRST_ROW_DROPPED), table.warnings)
    }

    @Test
    fun `one extra row that is not the smallest load is resampled instead`() {
        val table = map(listOf(50f, 10f, 20f, 30f), frames = 3)!!

        assertEquals(LoadMapping.RESAMPLED, table.mapping)
    }

    @Test
    fun `video frames pick the nearest logged time, clock starting at the reference`() {
        val table = map(
            loads = listOf(0f, 100f, 200f, 300f, 400f),
            frames = 2,
            times = listOf(10f, 10.5f, 11f, 11.5f, 12f),
            frameTimesMs = listOf(400L, 1600L),
        )!!

        // 10.4 s → row 10.5 (100 N); 11.6 s → row 11.5 (300 N).
        assertEquals(listOf(100f, 300f), table.loadsN)
        assertEquals(LoadMapping.TIME_NEAREST, table.mapping)
        assertEquals(listOf(LoadMapWarning.TIME_ALIGNED), table.warnings)
    }

    @Test
    fun `a log started after the reference frame is shifted by the gap`() {
        // Recording began 2 s before the machine: the log's first row is at video 2 s.
        val table = MachineLoadMapper.map(
            parsed(listOf(0f, 100f, 200f, 300f, 400f, 500f), listOf(0f, 1f, 2f, 3f, 4f, 5f)),
            frameCount = 3,
            frameTimesMs = listOf(1000L, 3000L, 5000L),
            testType = TestType.TENSILE,
            logStartS = 2f,
        )!!

        // Video 1 s is before the log began → first row; 3 s → log 1 s; 5 s → log 3 s.
        assertEquals(listOf(0f, 100f, 300f), table.loadsN)
        assertEquals(LoadMapping.TIME_NEAREST, table.mapping)
    }

    @Test
    fun `a frame past the end of the log takes the last row`() {
        val table = map(
            loads = listOf(0f, 25f, 50f, 100f),
            frames = 2,
            times = listOf(0f, 1f, 2f, 3f),
            frameTimesMs = listOf(500L, 9000L),
        )!!

        // A tie (0.5 s between rows 0 and 1) takes the earlier row.
        assertEquals(listOf(0f, 100f), table.loadsN)
    }

    @Test
    fun `resampling five rows onto three frames spaces them evenly after the reference`() {
        val table = map(listOf(0f, 10f, 20f, 30f, 40f), frames = 3)!!

        // Positions (i+1)·4/3: 1.33, 2.67, 4 → 13.3, 26.7, 40.
        assertEquals(3, table.loadsN.size)
        assertEquals(13.333f, table.loadsN[0], 1e-2f)
        assertEquals(26.667f, table.loadsN[1], 1e-2f)
        assertEquals(40f, table.loadsN[2], 1e-4f)
        assertEquals(LoadMapping.RESAMPLED, table.mapping)
        assertEquals(listOf(LoadMapWarning.RESAMPLED), table.warnings)
    }

    @Test
    fun `resampling three rows onto five frames interpolates`() {
        val table = map(listOf(0f, 10f, 20f), frames = 5)!!

        // Positions (i+1)·2/5: 0.4, 0.8, 1.2, 1.6, 2.
        assertEquals(listOf(4f, 8f, 12f, 16f, 20f), table.loadsN.map { Math.round(it * 1000f) / 1000f })
    }

    @Test
    fun `a single frame takes the last row`() {
        val table = map(listOf(0f, 10f, 20f, 30f), frames = 1)!!

        assertEquals(listOf(30f), table.loadsN)
    }

    @Test
    fun `a single row is repeated for every frame`() {
        val table = map(listOf(7f), frames = 3)!!

        assertEquals(listOf(7f, 7f, 7f), table.loadsN)
    }

    @Test
    fun `no frames or no rows is no table`() {
        assertNull(map(listOf(1f), frames = 0))
        assertNull(map(emptyList(), frames = 3))
    }

    @Test
    fun `loads keep their sign and bending has no sign to be surprised by`() {
        val negative = map(listOf(-10f, -20f), frames = 2, testType = TestType.BENDING)!!
        assertEquals(listOf(-10f, -20f), negative.loadsN)
        assertFalse(LoadMapWarning.SIGN_UNEXPECTED in negative.warnings)

        val positive = map(listOf(10f, 20f), frames = 2, testType = TestType.BENDING)!!
        assertEquals(listOf(10f, 20f), positive.loadsN)
        assertFalse(LoadMapWarning.SIGN_UNEXPECTED in positive.warnings)
    }

    @Test
    fun `a tensile log that is all negative is flagged, zeros ignored`() {
        val table = map(listOf(0f, -10f, -20f), frames = 3, testType = TestType.TENSILE)!!

        assertTrue(LoadMapWarning.SIGN_UNEXPECTED in table.warnings)
        assertFalse(LoadMapWarning.SIGN_UNEXPECTED in map(listOf(0f, 0f), frames = 2)!!.warnings)
    }
}
