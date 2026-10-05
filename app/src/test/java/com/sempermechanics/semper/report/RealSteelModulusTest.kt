@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ElasticModulus] on a real test, not a lab table: the app's own curve for
 * 1.4016 (X6Cr17) steel sheet, 12.5 mm² section, from Zenodo record 18311953
 * (CC BY 4.0). Strain is the virtual extensometer's ΔL / L₀ (ADR-012), read
 * from the emulator run's `.dat` files of 2026-09-29; stress is the dataset's
 * force over the area. Frames 36–40 are left out, as the app leaves them off
 * the curve: by then the strip has carried the far end band out of the
 * picture, so that band has no point. How the run was made and what it is
 * compared with: docs/app/REAL_WORLD_VALIDATION.md.
 */
class RealSteelModulusTest {

    private val model = StressStrain.Model.Axial(areaMm2 = 12.5f, axisX = true)

    private val strainToStress = listOf(
        0.06838f to 6.5040f, 0.08275f to 12.5520f, 0.11586f to 16.7600f, 0.11665f to 20.7120f,
        0.15304f to 24.5200f, 0.21176f to 29.5200f, 0.18970f to 34.6480f, 0.27418f to 40.1760f,
        0.25807f to 46.8800f, 0.33187f to 53.8480f, 0.40776f to 61.7440f, 0.44776f to 70.9440f,
        0.55585f to 80.0240f, 0.56723f to 89.6240f, 0.62246f to 100.9360f, 0.69089f to 111.1920f,
        0.77614f to 123.6880f, 0.84318f to 135.3920f, 0.93811f to 147.8800f, 1.01914f to 160.3760f,
        1.10720f to 173.0000f, 1.22561f to 187.3360f, 1.30805f to 200.0960f, 1.44172f to 212.7200f,
        1.49643f to 226.1360f, 1.68068f to 238.7600f, 1.79841f to 251.2560f, 1.95056f to 263.3520f,
        3.19049f to 300.7040f, 5.07586f to 311.7520f, 7.08284f to 317.9280f, 11.10189f to 326.0880f,
        20.82724f to 345.6800f, 39.47086f to 375.8000f, 66.86675f to 404.0720f,
    )

    private fun curve(): StressStrain.Curve =
        StressStrain.Curve(
            model,
            strainToStress.size,
            strainToStress.mapIndexed { i, (strain, stress) -> StressStrain.Point(i, stress * 12.5f, stress, strain) },
        )

    @Test
    fun `real steel gives E over the first 26 frames`() {
        val fit = ElasticModulus.fit(curve())!!

        assertEquals(148.85f, fit.modulusGPa, 0.05f)
        assertEquals(0, fit.firstFrame)
        assertEquals(25, fit.lastFrame)
        assertTrue(fit.r2 >= ElasticModulus.MIN_R2)
    }

    @Test
    fun `the first three frames alone are not straight, so the run must not stop there`() {
        // 6.5–16.8 MPa: strain noise is as large as the signal. The old rule gave up here.
        val first = curve().points.take(3)
        val line = LinearFit.fit(
            first.map { it.strainMilli.toDouble() }.toDoubleArray(),
            first.map { it.stressMPa.toDouble() }.toDoubleArray(),
        )!!

        assertEquals(0.897, line.r2, 1e-3)
    }

    @Test
    fun `the viewer's elastic region zooms to the fitted frames, not the 67 me curve`() {
        // Window 0–2.52 mε: the 26 fitted frames plus frames 27–28, led by the origin.
        val fit = ElasticModulus.fit(curve())!!

        val region = ElasticRegion.of(curve(), fit)!!

        assertEquals(29, region.points.size)
        assertEquals(strainToStress.take(28), region.points.drop(1))
        assertEquals(1.95056f, region.line.last().first, 0f)
    }

    @Test
    fun `Rp0_2 falls between frames 29 and 30, within 1 percent of the gauge points'`() {
        // The gauge points over all 751 logged steps give 307.8 MPa.
        val yieldPoint = YieldStrength.offset(curve(), ElasticModulus.fit(curve())!!)!!

        assertEquals(305.6f, yieldPoint.stressMPa, 0.1f)
        assertEquals(4.03f, yieldPoint.strainMilli, 0.01f)
        assertEquals(28, yieldPoint.frameBefore)
        assertEquals(29, yieldPoint.frameAfter)
        assertEquals(1f, yieldPoint.stressMPa / 307.8f, 0.01f)
    }

    @Test
    fun `E sits within ten percent of the dataset's own gauge points`() {
        // The dataset's stereo gauge points, 60 mm apart, over the same 26 frames.
        val gaugePointGPa = 157.5f

        val fit = ElasticModulus.fit(curve())!!

        assertEquals(1f, fit.modulusGPa / gaugePointGPa, 0.10f)
    }
}
