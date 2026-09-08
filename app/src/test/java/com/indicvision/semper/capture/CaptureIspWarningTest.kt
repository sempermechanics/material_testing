package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.CaptureIspApply
import com.indicvision.semper.ui.capture.CaptureIspLock
import com.indicvision.semper.ui.capture.CaptureIspLock.Key
import com.indicvision.semper.ui.capture.CaptureIspLock.Mode
import com.indicvision.semper.ui.capture.CaptureIspWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the user is told when the phone would not freeze its pipeline.
 *
 * Two failures matter equally here and pull in opposite directions: a refusal
 * the user is never told about leaves them trusting a precision the hardware did
 * not deliver, and a warning about a setting that actually worked teaches them to
 * ignore the next one. Both are tested.
 */
class CaptureIspWarningTest {

    private fun honoured(key: Key, honoured: Boolean?) =
        CaptureIspApply.Honoured(key, Mode.OFF, honoured)

    private fun planOf(vararg decisions: CaptureIspLock.Decision) =
        CaptureIspLock.Plan(decisions.toList())

    // ------------------------------------------------------------------
    // What counts as a shortfall
    // ------------------------------------------------------------------

    @Test
    fun `a phone that honoured everything produces no warning at all`() {
        val plan = planOf(
            CaptureIspLock.Decision(Key.OPTICAL_STABILIZATION, Mode.OFF, 0),
            CaptureIspLock.Decision(Key.SHADING, Mode.FAST, 1),
        )
        val report = listOf(
            honoured(Key.OPTICAL_STABILIZATION, true),
            honoured(Key.SHADING, true),
        )
        assertEquals(emptyList<Key>(), CaptureIspWarning.shortfall(plan, report))
        assertNull(CaptureIspWarning.headline(CaptureIspWarning.shortfall(plan, report)))
    }

    @Test
    fun `a key the HAL does not report is not treated as refused`() {
        // Null means the device echoed nothing back, so nothing can be
        // concluded. Warning here would fire on hardware that complied.
        val plan = planOf(CaptureIspLock.Decision(Key.EDGE, Mode.OFF, 0))
        val shortfall = CaptureIspWarning.shortfall(plan, listOf(honoured(Key.EDGE, null)))
        assertEquals(emptyList<Key>(), shortfall)
    }

    @Test
    fun `a key accepted and then ignored is a shortfall`() {
        val plan = planOf(CaptureIspLock.Decision(Key.NOISE_REDUCTION, Mode.OFF, 0))
        val shortfall =
            CaptureIspWarning.shortfall(plan, listOf(honoured(Key.NOISE_REDUCTION, false)))
        assertEquals(listOf(Key.NOISE_REDUCTION), shortfall)
    }

    @Test
    fun `a key that only ever got a fallback is a shortfall without any read-back`() {
        // The read-back cannot see this one: the request was never made, because
        // the device did not offer OFF. The plan is the only witness.
        val plan = planOf(CaptureIspLock.Decision(Key.EDGE, Mode.FAST, 1))
        assertEquals(listOf(Key.EDGE), CaptureIspWarning.shortfall(plan, emptyList()))
    }

    @Test
    fun `one key failing both ways is still counted once`() {
        // A LEGACY phone can land on FAST *and* then ignore it. The count drives
        // the hard-stop threshold, so double-counting would withdraw an override
        // the user was entitled to.
        val plan = planOf(CaptureIspLock.Decision(Key.EDGE, Mode.FAST, 1))
        val shortfall = CaptureIspWarning.shortfall(plan, listOf(honoured(Key.EDGE, false)))
        assertEquals(listOf(Key.EDGE), shortfall)
    }

    @Test
    fun `no plan means no claim either way`() {
        assertEquals(
            emptyList<Key>(),
            CaptureIspWarning.shortfall(null, listOf(honoured(Key.EDGE, false))),
        )
    }

    // ------------------------------------------------------------------
    // Which one gets named
    // ------------------------------------------------------------------

    @Test
    fun `the costliest refusal is the one named`() {
        // Stabilisation moves pixels outright; a tone curve shifts brightness.
        // Six warnings is noise, so the one shown has to be the worst one.
        val worst = CaptureIspWarning.headline(listOf(Key.TONEMAP, Key.OPTICAL_STABILIZATION))
        assertEquals(Key.OPTICAL_STABILIZATION, worst)
    }

    @Test
    fun `the shortfall comes back worst-first regardless of report order`() {
        val plan = planOf(
            CaptureIspLock.Decision(Key.AWB, Mode.UNAVAILABLE),
            CaptureIspLock.Decision(Key.VIDEO_STABILIZATION, Mode.UNAVAILABLE),
        )
        assertEquals(
            listOf(Key.VIDEO_STABILIZATION, Key.AWB),
            CaptureIspWarning.shortfall(plan, emptyList()),
        )
    }

    // ------------------------------------------------------------------
    // Every key can be spoken about
    // ------------------------------------------------------------------

    @Test
    fun `every key has an effect sentence, so no refusal is unexplainable`() {
        Key.entries.forEach { key ->
            assertTrue("$key has no effect string", CaptureIspWarning.effectOf(key) != 0)
        }
    }

    @Test
    fun `a real device's whole plan is describable`() {
        val legacy = CaptureIspLock.plan(
            CaptureIspLock.DeviceProfile(
                hardwareLevel = CaptureIspLock.HardwareLevel.LEGACY,
                manualSensor = false,
                manualPostProcessing = false,
            ),
        )
        val shortfall = CaptureIspWarning.shortfall(legacy, emptyList())
        assertFalse("a LEGACY profile should refuse something", shortfall.isEmpty())
        assertEquals(Key.OPTICAL_STABILIZATION, CaptureIspWarning.headline(shortfall))
    }
}
