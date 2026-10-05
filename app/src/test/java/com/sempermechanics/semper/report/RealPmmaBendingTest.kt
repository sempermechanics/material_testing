@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import com.indicvision.semper.data.BeamEdgeTaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [BeamDeflection] on a real 3-point bend, not a lab table: the app's own
 * deflection at the load point for a PMMA beam (span 75 mm, width 12 mm,
 * depth 31 mm) from Zenodo record 1172068 (CC BY 4.0). Each value is the
 * probe's mean displacement in pixels at the taps below, read from an
 * emulator run's `.dat` files on engine v0.2.3 (2026-10-01; subset 27, step 5;
 * the strain window does not touch displacement); the loads are the dataset's.
 * The authors' own DIC gives E from the graph 1.982 GPa and an average of
 * 2.084 GPa over the same probe. How the run was made:
 * docs/app/REAL_WORLD_VALIDATION.md, case 2.
 */
class RealPmmaBendingTest {

    // The 2026-09-24 run's taps, kept so the values compare: 570.76 px for 31 mm, straight down.
    private val taps = BeamEdgeTaps(1170.1666f, 38.833374f, 1170.1666f, 609.5925f)
    private val model = StressStrain.Model.Flexural(75f, 12f, 31f, true, BeamDeflection.Probe(taps, 31f))

    private val loadToDeflectionPx = listOf(
        35.2f to -0.0201f, 238.8f to 0.4407f, 485.3f to 1.1752f, 713.0f to 1.7534f,
        1003.8f to 2.4569f, 1283.3f to 3.1778f, 1567.8f to 3.9127f, 1858.6f to 4.6457f,
        2158.3f to 5.3975f, 2455.5f to 6.1431f, 2770.3f to 6.8779f, 3078.9f to 7.8490f,
        3392.5f to 8.5877f, 3711.1f to 9.6359f, 4019.7f to 10.3591f, 4339.6f to 11.2289f,
        4657.0f to 12.0991f, 4961.8f to 12.9217f, 5279.1f to 13.7277f, 5577.6f to 14.5520f,
        5884.9f to 15.4287f, 6183.3f to 16.2400f, 6479.2f to 17.1365f, 6767.5f to 17.9299f,
        7200.0f to 18.8472f, 7483.2f to 19.6723f, 7763.9f to 20.5340f, 8027.0f to 21.3222f,
        8302.6f to 22.1449f, 8561.9f to 22.9775f, 8827.4f to 23.8058f, 9079.0f to 24.6896f,
        9337.0f to 25.4795f,
    )

    private fun curve(): StressStrain.Curve {
        val mmPerPx = model.probe!!.mmPerPx
        val points = loadToDeflectionPx.mapIndexed { i, (w, px) ->
            StressStrain.Point(i, w, model.stressMPa(w), 0f, px * mmPerPx)
        }
        return StressStrain.Curve(model, points.size, points)
    }

    @Test
    fun `real PMMA gives the app's E from the graph and average`() {
        val summary = BeamDeflection.summarize(curve())!!

        assertEquals(0.054313f, summary.mmPerPx, 1e-6f)
        // Frame 1 (35 N) is under 1% of the peak: unloaded, out of the table.
        assertEquals(32, summary.loadSteps.size)
        assertEquals(6769.6, summary.slope!!.slope, 0.5)
        assertEquals(1.9972f, summary.slopeModulusGPa!!, 0.001f)
        // Frame 2 moves under 1 px, so it has no E of its own: 31 steps average.
        assertEquals(31, summary.loadSteps.count { it.modulusGPa != null })
        assertEquals(2.104f, summary.meanModulusGPa!!, 0.002f)
    }

    /**
     * The curve built from fields, as the app builds it: per frame, one
     * accepted point at the taps' midpoint moving [loadToDeflectionPx] px down
     * the image.
     */
    private fun built(taps: BeamEdgeTaps): StressStrain.Curve {
        val model = StressStrain.Model.Flexural(75f, 12f, 31f, true, BeamDeflection.Probe(taps, 31f))
        val field = { f: Int ->
            FloatArray(DicResult.STRIDE).also { d ->
                d[DicResult.IDX_X] = taps.midX
                d[DicResult.IDX_Y] = taps.midY
                d[DicResult.IDX_V] = loadToDeflectionPx[f].second
                d[DicResult.IDX_ZNSSD] = 0.05f
            }
        }
        return StressStrain.build(loadToDeflectionPx.map { it.first }, model, field)
    }

    @Test
    fun `the bottom edge tapped first gives the same positive E`() {
        // Pixel 6, 2026-09-26: this set with the bottom edge tapped first read
        // E from the graph −2.02 GPa and an average of −2.12 GPa.
        val bottomFirst = BeamEdgeTaps(taps.bottomX, taps.bottomY, taps.topX, taps.topY)

        listOf(taps, bottomFirst).forEach { t ->
            val summary = BeamDeflection.summarize(built(t))!!
            assertTrue(summary.loadSteps.last().deflectionMm > 0f)
            assertEquals(6769.6, summary.slope!!.slope, 0.5)
            assertEquals(1.9972f, summary.slopeModulusGPa!!, 0.001f)
            assertEquals(2.104f, summary.meanModulusGPa!!, 0.002f)
        }
    }

    @Test
    fun `both E values are within 1 percent of the authors' own DIC`() {
        val summary = BeamDeflection.summarize(curve())!!

        assertTrue(abs(summary.slopeModulusGPa!! / 1.982f - 1f) < 0.01f)
        assertTrue(abs(summary.meanModulusGPa!! / 2.084f - 1f) < 0.01f)
    }
}
