@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import com.indicvision.semper.data.BeamEdgeTaps
import com.indicvision.semper.data.SpecimenGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BeamDeflectionTest {

    private class P(val x: Float, val y: Float, val u: Float, val v: Float, val znssd: Float = 0.05f)

    private fun field(vararg points: P): FloatArray {
        val data = FloatArray(points.size * DicResult.STRIDE)
        points.forEachIndexed { k, p ->
            val i = k * DicResult.STRIDE
            data[i + DicResult.IDX_X] = p.x
            data[i + DicResult.IDX_Y] = p.y
            data[i + DicResult.IDX_U] = p.u
            data[i + DicResult.IDX_V] = p.v
            data[i + DicResult.IDX_ZNSSD] = p.znssd
        }
        return data
    }

    // Upright beam: top edge at y = 100, bottom at y = 200 — 100 px for 5 mm.
    private val upright = BeamEdgeTaps(topX = 500f, topY = 100f, bottomX = 500f, bottomY = 200f)

    @Test
    fun `taps below three pixels are not set and give no probe`() {
        assertFalse(BeamEdgeTaps.NONE.isSet)
        assertFalse(BeamEdgeTaps(10f, 10f, 10f, 12f).isSet)
        assertTrue(upright.isSet)
        assertNull(BeamDeflection.Probe.of(BeamEdgeTaps.NONE, 5f))
        assertNull(BeamDeflection.Probe.of(upright, 0f))
    }

    @Test
    fun `a uniform ten-pixel drop at 0 05 mm per pixel is half a millimetre`() {
        val probe = BeamDeflection.Probe(upright, thicknessMm = 5f)
        val data = field(P(500f, 150f, 0f, 10f), P(510f, 160f, 0.3f, 10f))

        assertEquals(0.05f, probe.mmPerPx, 1e-6f)
        assertEquals(10f, BeamDeflection.deflectionPx(data, probe)!!, 1e-5f)
        assertEquals(0.5f, BeamDeflection.deflectionMm(data, probe)!!, 1e-5f)
    }

    @Test
    fun `a phone held sideways reads the drop from U`() {
        // Top edge on the right, bottom on the left: "down" is −x in the image.
        val sideways = BeamEdgeTaps(topX = 300f, topY = 50f, bottomX = 200f, bottomY = 50f)
        val probe = BeamDeflection.Probe(sideways, thicknessMm = 5f)
        val data = field(P(250f, 50f, -4f, 0.2f))

        assertEquals(4f, BeamDeflection.deflectionPx(data, probe)!!, 1e-5f)
    }

    @Test
    fun `rejected points and points outside the radius are ignored`() {
        val probe = BeamDeflection.Probe(upright, thicknessMm = 5f)
        val data = field(
            P(500f, 150f, 0f, 2f),
            P(500f, 150f, 0f, 90f, znssd = 0.4f),
            P(700f, 150f, 0f, 90f),
        )

        assertEquals(50f, probe.radiusPx, 0f)
        assertEquals(2f, BeamDeflection.deflectionPx(data, probe)!!, 1e-5f)
        assertNull(BeamDeflection.deflectionPx(field(P(700f, 150f, 0f, 9f)), probe))
    }

    @Test
    fun `a thin beam still probes a minimum radius`() {
        val thin = BeamEdgeTaps(0f, 0f, 0f, 6f)

        assertEquals(BeamDeflection.MIN_PROBE_RADIUS_PX, BeamDeflection.Probe(thin, 1f).radiusPx, 0f)
    }

    @Test
    fun `M y over I is the flexural model's three P L over two b h squared`() {
        val model = StressStrain.Model.Flexural(935f, 150f, 6.38f, axisX = true)
        val w = 42f
        val sigma = BeamDeflection.momentNmm(w, 935f) * (6.38f / 2f) / BeamDeflection.secondMomentMm4(150f, 6.38f)

        assertEquals(model.stressMPa(w), sigma, 1e-4f)
    }

    // Bending.pdf: L = 93.5 cm, b = 15 cm, t = 6.38 mm; hanger kg, dial mm.
    private val labKg = floatArrayOf(4.28f, 5.24f, 6.92f, 8.68f, 10.06f, 11.92f)
    private val labMm = floatArrayOf(1.14f, 1.46f, 2.02f, 2.63f, 3.25f, 3.88f)

    /** A curve whose points carry the lab's dial readings as the DIC deflection. */
    private fun labCurve(spanMm: Float, g: Float): StressStrain.Curve {
        // 0.05 mm/px: 6.38 mm over 127.6 px.
        val taps = BeamEdgeTaps(0f, 0f, 0f, 127.6f)
        val model = StressStrain.Model.Flexural(spanMm, 150f, 6.38f, true, BeamDeflection.Probe(taps, 6.38f))
        val points = labKg.indices.map { i ->
            val w = labKg[i] * g
            StressStrain.Point(i, w, model.stressMPa(w), 0f, labMm[i])
        }
        return StressStrain.Curve(model, points.size, points)
    }

    @Test
    fun `the lab's steps give its bending stress and E`() {
        val summary = BeamDeflection.summarize(labCurve(935f, 9.81f))!!

        assertEquals(3246.18f, summary.secondMomentMm4, 0.01f)
        assertEquals(9.645f, summary.steps[0].stressMPa, 0.001f)
        assertEquals(193.21f, summary.steps[0].modulusGPa!!, 0.01f)
        assertEquals(173.58f, summary.meanModulusGPa!!, 0.01f)
        assertEquals(27.064, summary.slope!!.slope, 0.001)
        assertEquals(0.99814, summary.slope!!.r2, 1e-5)
        assertEquals(141.98f, summary.slopeModulusGPa!!, 0.01f)
    }

    @Test
    fun `with the report's own 930 mm and g of 9 8 the first step is its 189 93 GPa`() {
        val summary = BeamDeflection.summarize(labCurve(930f, 9.8f))!!

        assertEquals(189.93f, summary.steps[0].modulusGPa!!, 0.01f)
    }

    @Test
    fun `a video's frames collapse to one row per held load, unloaded frames left out`() {
        val lab = labCurve(935f, 9.81f)
        // The reference and two frames before the hanger went on, then each load filmed for four frames.
        val unloaded = (0 until 3).map { StressStrain.Point(it, 0f, 0f, 0f, 0f) }
        val held = lab.points.flatMap { p -> List(4) { p } }.mapIndexed { i, p -> p.copy(frame = i + unloaded.size) }
        val summary = BeamDeflection.summarize(lab.copy(points = unloaded + held, frameCount = 27))!!

        assertEquals(27, summary.steps.size)
        assertEquals(6, summary.loadSteps.size)
        assertEquals(listOf(3, 7, 11, 15, 19, 23), summary.loadSteps.map { it.frame })
        // With the zeros in, the slope would read 30.47 N/mm; without, the lab's 27.064.
        assertEquals(27.064, summary.slope!!.slope, 0.001)
        assertEquals(141.98f, summary.slopeModulusGPa!!, 0.01f)
        assertEquals(173.58f, summary.meanModulusGPa!!, 0.01f)
    }

    @Test
    fun `the slope line spans the measured load steps, not from zero deflection`() {
        // W = 25 δ - 15: from δ = 0 the line would start at -15 N and pull the load axis below zero (TD-94).
        val taps = BeamEdgeTaps(0f, 0f, 0f, 127.6f)
        val model = StressStrain.Model.Flexural(935f, 150f, 6.38f, true, BeamDeflection.Probe(taps, 6.38f))
        val unloaded = (0 until 2).map { StressStrain.Point(it, 0f, 0f, 0f, 0f) }
        val loaded = listOf(10f to 1.0f, 20f to 1.4f, 30f to 1.8f).mapIndexed { i, (w, d) ->
            StressStrain.Point(i + unloaded.size, w, model.stressMPa(w), 0f, d)
        }
        val summary = BeamDeflection.summarize(StressStrain.Curve(model, 5, unloaded + loaded))!!

        assertEquals(-15.0, summary.slope!!.intercept, 1e-3)
        val (first, last) = summary.slopeLine()!!
        assertEquals(1.0f, first.first, 1e-4f)
        assertEquals(10f, first.second, 1e-3f)
        assertEquals(1.8f, last.first, 1e-4f)
        assertEquals(30f, last.second, 1e-3f)
    }

    @Test
    fun `a step under a pixel of deflection has no E and is left out of the mean`() {
        val taps = BeamEdgeTaps(0f, 0f, 0f, 127.6f)
        val model = StressStrain.Model.Flexural(935f, 150f, 6.38f, true, BeamDeflection.Probe(taps, 6.38f))
        val points = listOf(
            StressStrain.Point(0, 5f, model.stressMPa(5f), 0f, 0.01f),
            StressStrain.Point(1, 42f, model.stressMPa(42f), 0f, 1.14f),
        )
        val summary = BeamDeflection.summarize(StressStrain.Curve(model, 2, points))!!

        assertNull(summary.steps[0].modulusGPa)
        assertEquals(summary.steps[1].modulusGPa!!, summary.meanModulusGPa!!, 1e-4f)
    }

    @Test
    fun `no summary without a probe or for tensile`() {
        val bare = StressStrain.Model.Flexural(935f, 150f, 6.38f, true)
        val axial = StressStrain.Model.Axial(12.5f, true)
        val point = StressStrain.Point(0, 10f, 1f, 0.1f)

        assertNull(BeamDeflection.summarize(StressStrain.Curve(bare, 1, listOf(point))))
        assertNull(BeamDeflection.summarize(StressStrain.Curve(axial, 1, listOf(point))))
    }

    @Test
    fun `a bending session with taps builds a probe and reads deflection per frame`() {
        val geometry = SpecimenGeometry(935f, 150f, 5f, upright)
        val model = StressStrain.Model.of("bending", 0f, true, geometry)
        val curve = StressStrain.build(listOf(10f, 20f), model, { f -> field(P(500f, 150f, 0f, 10f * (f + 1))) })

        assertTrue(model.plotsLoadDeflection)
        assertNotNull((model as StressStrain.Model.Flexural).probe)
        assertEquals(listOf(0.5f, 1f), curve.points.map { it.deflectionMm })
        assertEquals(listOf(0f to 0f, 0.5f to 10f, 1f to 20f), curve.plotPoints())
    }

    // The upright beam tapped bottom edge first: the probe's direction points up the image.
    private val bottomFirst = BeamEdgeTaps(topX = 500f, topY = 200f, bottomX = 500f, bottomY = 100f)

    /**
     * The lab's six loads on a 5 mm beam at 0.05 mm/px, built from fields as
     * the app does: an unloaded reference, then each frame's probe point
     * dropping the lab's dial reading. [loadSign] −1 is a machine that logs
     * the load as negative.
     */
    private fun builtCurve(taps: BeamEdgeTaps, loadSign: Float = 1f): StressStrain.Curve {
        val model = StressStrain.Model.of("bending", 0f, true, SpecimenGeometry(935f, 150f, 5f, taps))
        val loads = listOf(0f) + labKg.map { loadSign * it * 9.81f }
        val dropsPx = listOf(0f) + labMm.map { it / 0.05f }
        return StressStrain.build(loads, model, { f -> field(P(500f, 150f, 0f, dropsPx[f])) })
    }

    @Test
    fun `taps in either order give the same positive deflection and E`() {
        // One frame on its own reads the drop against the taps' direction...
        val drop = field(P(500f, 150f, 0f, 10f))
        assertEquals(-10f, BeamDeflection.deflectionPx(drop, BeamDeflection.Probe(bottomFirst, 5f))!!, 1e-5f)

        // ...but a curve signs δ by its loads.
        val inOrder = BeamDeflection.summarize(builtCurve(upright))!!
        val swapped = BeamDeflection.summarize(builtCurve(bottomFirst))!!

        assertEquals(inOrder.steps.size, swapped.steps.size)
        inOrder.steps.zip(swapped.steps).forEach { (a, b) -> assertEquals(a.deflectionMm, b.deflectionMm, 0f) }
        assertEquals(labMm.last(), swapped.loadSteps.last().deflectionMm, 1e-5f)
        assertEquals(inOrder.slope!!.slope, swapped.slope!!.slope, 0.0)
        assertTrue(swapped.slope!!.slope > 0.0)
        assertTrue(swapped.slopeModulusGPa!! > 0f)
        assertEquals(inOrder.slopeModulusGPa!!, swapped.slopeModulusGPa!!, 0f)
        assertTrue(swapped.meanModulusGPa!! > 0f)
        assertEquals(inOrder.meanModulusGPa!!, swapped.meanModulusGPa!!, 0f)
        assertEquals(inOrder.loadSteps.map { it.modulusGPa }, swapped.loadSteps.map { it.modulusGPa })
    }

    @Test
    fun `a load logged as negative still gives a positive E`() {
        val positive = BeamDeflection.summarize(builtCurve(upright))!!
        val negative = BeamDeflection.summarize(builtCurve(upright, loadSign = -1f))!!

        assertTrue(negative.loadSteps.all { it.loadN < 0f && it.deflectionMm < 0f })
        assertEquals(positive.slopeModulusGPa!!, negative.slopeModulusGPa!!, 1e-4f)
        assertEquals(positive.meanModulusGPa!!, negative.meanModulusGPa!!, 1e-4f)
    }

    @Test
    fun `points already along the load, or with no deflection, come back as they are`() {
        val along = listOf(StressStrain.Point(0, 10f, 1f, 0f, 0.5f), StressStrain.Point(1, 20f, 2f, 0f, 1f))
        val tensile = listOf(StressStrain.Point(0, 10f, 1f, 0.1f))
        val against = along.map { it.copy(deflectionMm = -it.deflectionMm!!) }

        assertSame(along, BeamDeflection.alongLoad(along))
        assertSame(tensile, BeamDeflection.alongLoad(tensile))
        assertEquals(listOf(0.5f, 1f), BeamDeflection.alongLoad(against).map { it.deflectionMm })
    }

    @Test
    fun `a bending session from before the tap keeps stress on strain`() {
        val model = StressStrain.Model.of("bending", 0f, true, SpecimenGeometry(935f, 150f, 5f))

        assertFalse(model.plotsLoadDeflection)
        assertTrue(model.isComplete)
    }
}
