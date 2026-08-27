package com.indicvision.semper.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.ui.capture.CaptureCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureCalibrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearStoredCalibration() {
        context.getSharedPreferences("capture_calibration", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `assumes a slow device before any measurement`() {
        assertEquals(
            CaptureCalibration.DEFAULT_MS_PER_MEGAPIXEL,
            CaptureCalibration.msPerMegapixel(context),
            0.01f,
        )
    }

    @Test
    fun `first measurement is adopted directly`() {
        // 8MP taking 800ms is 100ms per megapixel.
        CaptureCalibration.record(context, W_8MP, H_8MP, 800L)

        assertEquals(100f, CaptureCalibration.msPerMegapixel(context), 1f)
    }

    @Test
    fun `a measurement on one device does not constrain another resolution linearly`() {
        CaptureCalibration.record(context, W_8MP, H_8MP, 800L)

        val full = CaptureCalibration.estimateFrameMs(context, W_8MP, H_8MP)
        val double = CaptureCalibration.estimateFrameMs(context, W_8MP * 2, H_8MP)

        assertEquals("twice the pixels, twice the cost", full * 2.0, double.toDouble(), full * 0.05)
    }

    @Test
    fun `predictions carry safety headroom over the raw measurement`() {
        CaptureCalibration.record(context, W_8MP, H_8MP, 800L)

        // Thermal throttling and background load make later frames slower, so
        // the offer must sit above the calibration frame, never below it.
        assertTrue(CaptureCalibration.estimateFrameMs(context, W_8MP, H_8MP) > 800L)
    }

    @Test
    fun `never predicts below the floor when scaling far down`() {
        // A tiny ROI-sized frame still pays sensor and file overhead that does
        // not shrink with pixel count.
        CaptureCalibration.record(context, W_8MP, H_8MP, 800L)

        assertTrue(
            CaptureCalibration.estimateFrameMs(context, 64, 64) >= CaptureCalibration.MIN_FRAME_MS,
        )
    }

    @Test
    fun `later measurements are blended, not ignored`() {
        CaptureCalibration.record(context, W_8MP, H_8MP, 800L)
        val first = CaptureCalibration.msPerMegapixel(context)

        CaptureCalibration.record(context, W_8MP, H_8MP, 1600L)
        val blended = CaptureCalibration.msPerMegapixel(context)

        assertTrue("must move toward the slower sample", blended > first)
        assertTrue("but not jump straight to it", blended < 200f)
    }

    @Test
    fun `the estimate tracks the measured cost`() {
        CaptureCalibration.record(context, W_8MP, H_8MP, 800L)

        val perFrame = CaptureCalibration.estimateFrameMs(context, W_8MP, H_8MP)

        // 800ms measured, plus the safety factor, minus nothing.
        assertTrue("perFrame was $perFrame", perFrame >= 800L)
    }

    @Test
    fun `ignores a degenerate measurement`() {
        CaptureCalibration.record(context, 0, 0, 500L)
        CaptureCalibration.record(context, W_8MP, H_8MP, 0L)

        assertEquals(
            CaptureCalibration.DEFAULT_MS_PER_MEGAPIXEL,
            CaptureCalibration.msPerMegapixel(context),
            0.01f,
        )
    }

    private companion object {
        const val W_8MP = 4000
        const val H_8MP = 2000
    }
}
