@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ElasticModulus] on a real test, not a lab table: the app's own curve for
 * 1.4016 (X6Cr17) steel sheet, 12.5 mm² section, from Zenodo record 18311953
 * (CC BY 4.0). Strain is the app's mean Exx over the analysed region, read
 * from the emulator run's `.dat` files; stress is the dataset's force over
 * the area. How the run was made and what it is compared with:
 * docs/app/REAL_WORLD_VALIDATION.md.
 */
class RealSteelModulusTest {

    private val model = StressStrain.Model.Axial(areaMm2 = 12.5f, axisX = true)

    private val strainToStress = listOf(
        0.06233f to 6.5040f, 0.07823f to 12.5520f, 0.11083f to 16.7600f, 0.11317f to 20.7120f,
        0.14982f to 24.5200f, 0.20634f to 29.5200f, 0.18220f to 34.6480f, 0.26908f to 40.1760f,
        0.25411f to 46.8800f, 0.32075f to 53.8480f, 0.39339f to 61.7440f, 0.44534f to 70.9440f,
        0.54909f to 80.0240f, 0.55854f to 89.6240f, 0.61863f to 100.9360f, 0.68188f to 111.1920f,
        0.76759f to 123.6880f, 0.83523f to 135.3920f, 0.92987f to 147.8800f, 1.00636f to 160.3760f,
        1.10788f to 173.0000f, 1.21272f to 187.3360f, 1.29871f to 200.0960f, 1.43194f to 212.7200f,
        1.48673f to 226.1360f, 1.66117f to 238.7600f, 1.79370f to 251.2560f, 1.93949f to 263.3520f,
        3.18195f to 300.7040f, 5.03325f to 311.7520f, 7.03861f to 317.9280f, 11.08688f to 326.0880f,
        21.06555f to 345.6800f, 40.86319f to 375.8000f, 71.29121f to 404.0720f, 114.46159f to 423.9360f,
        161.07530f to 432.2160f, 213.92294f to 435.5040f, 268.07956f to 433.5360f, 336.13388f to 422.0880f,
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

        assertEquals(149.58f, fit.modulusGPa, 0.05f)
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

        assertEquals(0.913, line.r2, 1e-3)
    }

    @Test
    fun `the viewer's elastic region zooms to the fitted frames, not the 336 me curve`() {
        // Window 0–2.49 mε: the 26 fitted frames plus frames 27–28, led by the origin.
        val fit = ElasticModulus.fit(curve())!!

        val region = ElasticRegion.of(curve(), fit)!!

        assertEquals(29, region.points.size)
        assertEquals(strainToStress.take(28), region.points.drop(1))
        assertEquals(1.93949f, region.line.last().first, 0f)
    }

    @Test
    fun `E sits within ten percent of the dataset's own gauge points`() {
        // The dataset's stereo gauge points, 60 mm apart, over the same 26 frames.
        val gaugePointGPa = 157.5f

        val fit = ElasticModulus.fit(curve())!!

        assertEquals(1f, fit.modulusGPa / gaugePointGPa, 0.10f)
    }
}
