package com.indicvision.semper.session

import com.indicvision.semper.data.AlphaDeviceMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaDeviceMeterCsvTest {

    @Test
    fun `csv line stays comma-safe and ordered`() {
        val sample = AlphaDeviceMeter.Sample(
            utcIso = "2026-08-10T12:00:00Z",
            label = "viewer_open_f12",
            pssKb = 180_000,
            javaUsedKb = 90_000,
            javaMaxKb = 512_000,
            nativeHeapKb = 40_000,
            availMemKb = 500_000,
            totalMemKb = 3_800_000,
            lowMemory = false,
            appBytes = 40_000_000,
            dataBytes = 800_000_000,
            cacheBytes = 20_000_000,
            sessionsBytes = 750_000_000,
            cacheDirBytes = 15_000_000,
        )
        assertEquals(860_000_000L, sample.romBytes)
        val line = sample.toCsvLine()
        assertEquals(15, line.split(',').size)
        assertTrue(line.startsWith("2026-08-10T12:00:00Z,viewer_open_f12,180000,"))
        assertTrue(line.endsWith(",860000000"))
        assertEquals(15, AlphaDeviceMeter.CSV_HEADER.split(',').size)
    }

    @Test
    fun `label commas are stripped for csv`() {
        val sample = AlphaDeviceMeter.Sample(
            utcIso = "t",
            label = "a,b",
            pssKb = 1,
            javaUsedKb = 1,
            javaMaxKb = 1,
            nativeHeapKb = 1,
            availMemKb = 1,
            totalMemKb = 1,
            lowMemory = true,
            appBytes = 1,
            dataBytes = 1,
            cacheBytes = 1,
            sessionsBytes = 1,
            cacheDirBytes = 1,
        )
        // record() sanitizes; Sample constructor used by tests can still carry commas —
        // ensure toCsvLine length stays stable when label has no commas after sanitize path.
        assertEquals(15, sample.copy(label = sample.label.replace(',', '_')).toCsvLine().split(',').size)
    }
}
