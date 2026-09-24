package com.indicvision.semper.results

import com.indicvision.semper.report.PdfReportGenerator
import com.indicvision.semper.report.PdfReportGenerator.Progress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * The report's progress contract and failure handling: the share sheet drives
 * its bar from these percents and treats [Progress.Error] as the only failure
 * signal, so a throw inside a render must arrive as Error, never escape.
 *
 * Robolectric's PdfDocument has no native document (`startPage` throws
 * "document is closed"), so drawing pages is covered on a device by
 * `PdfReportDeviceTest`; these cases never start a page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfReportGeneratorTest {

    private fun statuses(events: List<Progress>) = events.filterIsInstance<Progress.Status>()

    @Test
    fun `batch progress spreads frames over the middle of the bar`() {
        val events = runBlocking {
            PdfReportGenerator.generateBatch(4, { null }, ByteArrayOutputStream()).toList()
        }
        assertEquals(listOf(2, 25, 48, 71), statuses(events).map { it.percent })
        assertEquals("Frame 1 of 4…", statuses(events).first().message)
    }

    @Test
    fun `batch with every frame unreadable has no telemetry page`() {
        val events = runBlocking {
            PdfReportGenerator.generateBatch(2, { null }, ByteArrayOutputStream()).toList()
        }
        assertTrue(statuses(events).none { it.message.startsWith("Compiling") })
    }

    @Test
    fun `a throwing frame source ends in Error, not an exception`() {
        val events = runBlocking {
            PdfReportGenerator.generateBatch(2, { error("unreadable .dat") }, ByteArrayOutputStream()).toList()
        }
        val last = events.last()
        assertTrue(last is Progress.Error)
        assertEquals("unreadable .dat", (last as Progress.Error).ex.message)
    }
}
