package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.CaptureBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureBudgetTest {

    @Test
    fun `ram required is two full ARGB frames`() {
        assertEquals(1920L * 1080L * 4L * 2L, CaptureBudget.ramRequired(1920, 1080))
    }

    @Test
    fun `storage stills uses bytes per frame when known`() {
        assertEquals(10_000L * 5, CaptureBudget.storageForStills(100, 100, 5, bytesPerFrame = 10_000L))
    }

    @Test
    fun `check fails when ram below 1_5x`() {
        val est = CaptureBudget.estimateStills(1000, 1000, 10)
        val need = (est.ramRequiredBytes * CaptureBudget.RAM_FACTOR).toLong()
        val check = CaptureBudget.check(est, availRamBytes = need - 1, availStorageBytes = Long.MAX_VALUE)
        assertFalse(check.ok)
        assertFalse(check.ramOk)
        assertTrue(check.storageOk)
    }

    @Test
    fun `check fails when storage below 1_25x`() {
        val est = CaptureBudget.estimateStills(1000, 1000, 10, bytesPerFrame = 1_000_000L)
        val need = (est.storageRequiredBytes * CaptureBudget.STORAGE_FACTOR).toLong()
        val check = CaptureBudget.check(est, availRamBytes = Long.MAX_VALUE, availStorageBytes = need - 1)
        assertFalse(check.ok)
        assertTrue(check.ramOk)
        assertFalse(check.storageOk)
    }

    @Test
    fun `check passes at exact thresholds`() {
        val est = CaptureBudget.estimateVideo(1920, 1080, 10, 50, bytesPerExtractedFrame = 500_000L)
        val ramNeed = (est.ramRequiredBytes * CaptureBudget.RAM_FACTOR).toLong()
        val storageNeed = (est.storageRequiredBytes * CaptureBudget.STORAGE_FACTOR).toLong()
        val check = CaptureBudget.check(est, ramNeed, storageNeed)
        assertTrue(check.ok)
    }

    @Test
    fun `post test-shot larger jpeg increases storage need`() {
        val before = CaptureBudget.estimateStills(4000, 3000, 20)
        // Catalogue estimate is ~1 byte/pixel; a real JPEG can be larger.
        val after = CaptureBudget.estimateStills(
            4000,
            3000,
            20,
            bytesPerFrame = before.storageRequiredBytes / 20 + 2_000_000L,
        )
        assertTrue(after.storageRequiredBytes > before.storageRequiredBytes)
    }
}
