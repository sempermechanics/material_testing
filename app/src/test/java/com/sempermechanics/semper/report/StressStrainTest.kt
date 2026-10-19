@file:Suppress("MagicNumber")

package com.sempermechanics.semper.report

import com.sempermechanics.semper.data.mechanical.BeamEdgeTaps
import com.sempermechanics.semper.data.mechanical.CurveCorrection
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.field.DicResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StressStrainTest {

    /**
     * A field of [n] × [n] accepted points 10 px apart, stretched evenly: Exx /
     * Eyy (in strain, not mε) and the displacements that give them, so the mean
     * strain and the extensometer's ΔL / L₀ agree.
     */
    private fun field(exx: Float, eyy: Float, n: Int = 4, accepted: Boolean = true): FloatArray {
        val data = FloatArray(n * n * DicResult.STRIDE)
        var i = 0
        for (row in 0 until n) {
            for (col in 0 until n) {
                val x = col * 10f
                val y = row * 10f
                data[i + DicResult.IDX_X] = x
                data[i + DicResult.IDX_Y] = y
                data[i + DicResult.IDX_U] = exx * x
                data[i + DicResult.IDX_V] = eyy * y
                data[i + DicResult.IDX_EXX] = exx
                data[i + DicResult.IDX_EYY] = eyy
                data[i + DicResult.IDX_ZNSSD] = if (accepted) 0.05f else -1f
                i += DicResult.STRIDE
            }
        }
        return data
    }

    private fun axialStrain(axisX: Boolean, gaugeOn: FloatArray, data: FloatArray): Float? =
        StressStrain.Model.Axial(1f, axisX).strainMilli(data, Extensometer.gauge(gaugeOn, axisX))

    @Test
    fun `axial stress is load over area in megapascals and NaN without an area`() {
        val model = StressStrain.Model.Axial(areaMm2 = 12.5f, axisX = true)

        assertEquals(100f, model.stressMPa(1250f), 1e-4f)
        assertEquals(-8f, model.stressMPa(-100f), 1e-4f)
        assertTrue(StressStrain.Model.Axial(areaMm2 = 0f, axisX = true).stressMPa(100f).isNaN())
    }

    @Test
    fun `axial strain is the extensometer's delta L over L0 along the load axis in millistrain`() {
        val unloaded = field(0f, 0f)
        val data = field(exx = 0.002f, eyy = -0.0006f)

        assertEquals(2f, axialStrain(axisX = true, unloaded, data)!!, 1e-3f)
        assertEquals(-0.6f, axialStrain(axisX = false, unloaded, data)!!, 1e-3f)
        assertNull(axialStrain(axisX = true, unloaded, field(0.1f, 0.1f, accepted = false)))
        assertNull(StressStrain.Model.Axial(1f, axisX = true).strainMilli(data, gauge = null))
    }

    @Test
    fun `axial strain follows the end bands, not the mean of the field`() {
        // Necking: the middle strains ten times the ends, so the mean over the
        // region and the gauge's ΔL / L₀ part.
        val unloaded = field(0f, 0f, n = 11)
        val necked = unloaded.copyOf()
        var i = 0
        while (i < necked.size) {
            val x = necked[i + DicResult.IDX_X]
            // u(x): 1 mε up to x = 40, 10 mε from 40 to 60, 1 mε beyond.
            necked[i + DicResult.IDX_U] = 0.001f * minOf(x, 40f) +
                0.01f * (x.coerceIn(40f, 60f) - 40f) + 0.001f * (maxOf(x, 60f) - 60f)
            i += DicResult.STRIDE
        }

        // Bands 0–10 and 90–100 px: mean positions 5 and 95 → L₀ = 90 px.
        // ΔL = u(95) − u(5) = (0.04 + 0.2 + 0.035) − 0.005 = 0.27 px → 3 mε.
        assertEquals(3f, axialStrain(axisX = true, unloaded, necked)!!, 1e-3f)
    }

    @Test
    fun `build skips unreadable frames and keeps the load sign`() {
        val fields = listOf(field(0.001f, 0f), null, field(0.003f, 0f))
        val visited = mutableListOf<Int>()

        val curve = StressStrain.build(
            loadsN = listOf(-100f, -200f, -300f),
            model = StressStrain.Model.Axial(areaMm2 = 10f, axisX = true),
            frameData = { fields[it] },
            onProgress = { visited += it },
        )

        assertEquals(3, curve.frameCount)
        assertEquals(listOf(0, 2), curve.points.map { it.frame })
        assertEquals(-10f, curve.points[0].stressMPa, 1e-4f)
        assertEquals(1f, curve.points[0].strainMilli, 1e-3f)
        assertEquals(-30f, curve.points[1].stressMPa, 1e-4f)
        assertEquals(listOf(1, 2, 3), visited)
        assertNull(curve.at(1))
        assertEquals(2, curve.peak!!.frame)
    }

    @Test
    fun `the peak stress counts a loaded frame with no strain, but the curve's peak does not`() {
        // Frame 2 carries the highest load, but its field has no accepted point
        // (a band left the picture), so it has no strain and no point (TD-147).
        val fields = listOf(field(0.001f, 0f), field(0.002f, 0f), field(0.003f, 0f, accepted = false))

        val curve = StressStrain.build(
            loadsN = listOf(100f, 200f, 300f),
            model = StressStrain.Model.Axial(areaMm2 = 10f, axisX = true),
            frameData = { fields[it] },
        )

        assertEquals(listOf(0, 1), curve.points.map { it.frame })
        assertEquals(1, curve.peak!!.frame)
        assertEquals(StressStrain.Peak(2, 300f, 30f, onCurve = false), curve.peakStress)
    }

    @Test
    fun `the peak stress is the curve's own when the top load has a strain`() {
        val curve = StressStrain.build(
            loadsN = listOf(100f, -300f, 200f),
            model = StressStrain.Model.Axial(areaMm2 = 10f, axisX = true),
            frameData = { field(0.001f * (it + 1), 0f) },
        )

        assertEquals(StressStrain.Peak(1, -300f, -30f, onCurve = true), curve.peakStress)
        assertEquals(curve.peak!!.frame, curve.peakStress!!.frame)
    }

    @Test
    fun `a curve made from points alone reports its own peak`() {
        val model = StressStrain.Model.Axial(areaMm2 = 10f, axisX = true)
        val points = listOf(StressStrain.Point(0, 100f, 10f, 1f), StressStrain.Point(1, 50f, 5f, 2f))
        val curve = StressStrain.Curve(model, 2, points)

        assertEquals(StressStrain.Peak(0, 100f, 10f, onCurve = true), curve.peakStress)
        assertNull(StressStrain.Curve(model, 0, emptyList()).peakStress)
    }

    @Test
    fun `a frame with no matched load is left off the curve and its field is not read`() {
        val read = mutableListOf<Int>()

        val curve = StressStrain.build(
            loadsN = listOf(10f, Float.NaN, 30f),
            model = StressStrain.Model.Axial(areaMm2 = 10f, axisX = true),
            frameData = {
                read += it
                field(0.001f, 0f)
            },
        )

        assertEquals(listOf(0, 2), curve.points.map { it.frame })
        assertEquals(listOf(0, 2), read)
    }

    @Test
    fun `plot points start at the origin and are strain then stress`() {
        val curve = StressStrain.build(
            loadsN = listOf(50f),
            model = StressStrain.Model.Axial(areaMm2 = 5f, axisX = false),
            frameData = { field(0f, 0.0015f) },
        )

        assertEquals(listOf(0f to 0f, 1.5f to 10f), curve.plotPoints())
        assertTrue(StressStrain.Curve(StressStrain.Model.Axial(1f, true), 0, emptyList()).isEmpty)
    }

    @Test
    fun `a tensile correction moves the plotted reference to its bias`() {
        // ε = 1 × ε + 0.5 mε, σ = 1 × σ + 10 MPa: the unloaded reference plots at (0.5, 10), not (0, 0).
        val correction = CurveCorrection(strainBiasMilli = 0.5f, stressBiasMPa = 10f)
        val curve = StressStrain.build(
            loadsN = listOf(50f),
            model = StressStrain.Model.Axial(areaMm2 = 5f, axisX = false, correction = correction),
            frameData = { field(0f, 0.0015f) },
        )

        assertEquals(0.5f to 10f, curve.plotPoints().first())
        assertEquals(2f, curve.plotPoints()[1].first, 1e-4f)
        assertEquals(20f, curve.plotPoints()[1].second, 1e-4f)
    }

    @Test
    fun `flexural stress is three-point bending at the outer fibre`() {
        // σ = 3 P L / (2 b h²): 3 · 200 · 80 / (2 · 10 · 4²) = 48000 / 320 = 150 MPa
        val model = StressStrain.Model.Flexural(spanMm = 80f, widthMm = 10f, thicknessMm = 4f, axisX = true)

        assertTrue(model.isComplete)
        assertEquals(150f, model.stressMPa(200f), 1e-3f)
        assertEquals(-150f, model.stressMPa(-200f), 1e-3f)
        assertEquals(listOf(80f, 10f, 4f), model.dimensions.map { it.second })
    }

    /**
     * The beam's side face, 100 px thick between taps at y = 0 and y = 100 and
     * 100 px along it: Exx = [k] (y − 50), linear through the thickness and zero
     * on the neutral axis, and every point moved [vPx] down.
     */
    private fun bentField(k: Float, vPx: Float): FloatArray {
        val data = FloatArray(11 * 11 * DicResult.STRIDE)
        var i = 0
        for (row in 0..10) {
            for (col in 0..10) {
                data[i + DicResult.IDX_X] = col * 10f
                data[i + DicResult.IDX_Y] = row * 10f
                data[i + DicResult.IDX_V] = vPx
                data[i + DicResult.IDX_EXX] = k * (row * 10f - 50f)
                data[i + DicResult.IDX_ZNSSD] = 0.05f
                i += DicResult.STRIDE
            }
        }
        return data
    }

    private fun bending(taps: BeamEdgeTaps) =
        StressStrain.Model.Flexural(200f, 10f, 10f, axisX = true, probe = BeamDeflection.Probe(taps, 10f))

    @Test
    fun `bending strain is the outer fibre's, from the gradient through the thickness`() {
        // ±1 mε at the two faces (k = 2×10⁻⁵ per px over ±50 px). The field's
        // mean, which the curve plotted before, is 0.
        val data = bentField(k = 2e-5f, vPx = 0f)

        assertEquals(1.0f, bending(BeamEdgeTaps(50f, 0f, 50f, 100f)).strainMilli(data, gauge = null)!!, 0.05f)
        assertEquals(0f, DicResult.fieldStats(data, DicResult.IDX_EXX)!![2], 1e-6f)
    }

    /**
     * A pure-bending side face whose beam runs at [degrees] to the image's x
     * axis (y down): along the beam ε = [k] η, across it −ν k η with ν = 0.3, no
     * shear in the beam's frame, η the distance from the neutral axis through
     * (200, 150). Image components by the tensor rotation, Exy tensor shear as
     * the engine writes it. Taps 100 px apart across the beam, through that
     * point, top first.
     */
    private fun tiltedBend(degrees: Double, k: Double = 2e-5): Pair<FloatArray, BeamEdgeTaps> {
        val t = Math.toRadians(degrees)
        val c = Math.cos(t)
        val s = Math.sin(t)
        val nu = 0.3
        val data = FloatArray(81 * 81 * DicResult.STRIDE)
        var i = 0
        for (row in 0..80) {
            for (col in 0..80) {
                val x = col * 5.0
                val y = row * 5.0
                val eta = -(x - 200.0) * s + (y - 150.0) * c
                val along = k * eta
                val across = -nu * k * eta
                data[i + DicResult.IDX_X] = x.toFloat()
                data[i + DicResult.IDX_Y] = y.toFloat()
                data[i + DicResult.IDX_EXX] = (along * c * c + across * s * s).toFloat()
                data[i + DicResult.IDX_EYY] = (along * s * s + across * c * c).toFloat()
                data[i + DicResult.IDX_EXY] = ((along - across) * c * s).toFloat()
                data[i + DicResult.IDX_ZNSSD] = 0.05f
                i += DicResult.STRIDE
            }
        }
        val taps = BeamEdgeTaps(
            (200.0 + 50.0 * s).toFloat(),
            (150.0 - 50.0 * c).toFloat(),
            (200.0 - 50.0 * s).toFloat(),
            (150.0 + 50.0 * c).toFloat(),
        )
        return data to taps
    }

    @Test
    fun `a beam tilted in the photo reads the strain along the beam, not Exx`() {
        // k = 2×10⁻⁵ per px over ±50 px: 1 mε at the faces whatever the tilt
        // (numpy, the same lattice and section). Exx as it is read 0.9608 mε
        // at 10° (−3.9 %) and 0.675 at 30° (−32.5 %): Poisson's ratio in Eyy
        // and the shear leak into it as the beam turns.
        listOf(0.0, 10.0, -10.0, 30.0).forEach { degrees ->
            val (data, taps) = tiltedBend(degrees)

            val strain = bending(taps).strainMilli(data, gauge = null)!!

            assertEquals("tilt $degrees°", 1.0f, strain, 0.02f)
        }
    }

    @Test
    fun `a beam upright in the photo reads Eyy from its side-by-side taps`() {
        val (data, taps) = tiltedBend(90.0)

        assertEquals(1.0f, bending(taps).strainMilli(data, gauge = null)!!, 0.02f)
        assertEquals("outer-fibre Eyy", bending(taps).strainName)
        assertEquals("outer-fibre Exx", bending(BeamEdgeTaps(50f, 0f, 50f, 100f)).strainName)
        assertEquals("outer-fibre strain along the beam", bending(tiltedBend(10.0).second).strainName)
    }

    @Test
    fun `the bending strain is the tension face's whichever edge was tapped first`() {
        // Loads push the beam down the photo (+v), so the bottom face is in tension.
        val loads = listOf(10f, 20f)
        val frames = { f: Int -> bentField(k = 2e-5f * (f + 1), vPx = 2f * (f + 1)) }

        val topFirst = StressStrain.build(loads, bending(BeamEdgeTaps(50f, 0f, 50f, 100f)), frames)
        val bottomFirst = StressStrain.build(loads, bending(BeamEdgeTaps(50f, 100f, 50f, 0f)), frames)

        listOf(topFirst, bottomFirst).forEach { curve ->
            assertEquals(listOf(1f, 2f), curve.points.map { Math.round(it.strainMilli * 100f) / 100f })
            assertEquals(listOf(0.2f, 0.4f), curve.points.map { Math.round(it.deflectionMm!! * 100f) / 100f })
        }
    }

    @Test
    fun `bending without the edge taps measures no strain but keeps its stress`() {
        val model = StressStrain.Model.Flexural(spanMm = 80f, widthMm = 10f, thicknessMm = 4f, axisX = true)
        val curve = StressStrain.build(listOf(200f), model, { field(exx = 0.002f, eyy = -0.0006f) })

        assertFalse(model.measuresStrain)
        assertTrue(model.strainMilli(field(exx = 0.002f, eyy = -0.0006f), gauge = null)!!.isNaN())
        assertEquals(150f, curve.points.single().stressMPa, 1e-3f)
        // Only the origin: no strain to plot against.
        assertEquals(listOf(0f to 0f), curve.plotPoints())
        assertTrue(StressStrain.Model.Axial(1f, true).measuresStrain)
        assertTrue(bending(BeamEdgeTaps(50f, 0f, 50f, 100f)).measuresStrain)
    }

    @Test
    fun `a model with a missing dimension is incomplete and gives NaN, never a stress`() {
        assertFalse(StressStrain.Model.Axial(0f, true).isComplete)
        assertTrue(StressStrain.Model.Axial(0f, true).stressMPa(10f).isNaN())
        assertFalse(StressStrain.Model.Flexural(80f, 0f, 4f, true).isComplete)
        assertTrue(StressStrain.Model.Flexural(80f, 0f, 4f, true).stressMPa(10f).isNaN())
    }

    @Test
    fun `the model follows the stored test type and falls back to axial`() {
        val geometry = SpecimenGeometry(spanMm = 80f, widthMm = 10f, thicknessMm = 4f)

        assertEquals(
            StressStrain.Model.Flexural(80f, 10f, 4f, axisX = false),
            StressStrain.Model.of("bending", 12.5f, loadAxisX = false, geometry),
        )
        assertEquals(StressStrain.Model.Axial(12.5f, true), StressStrain.Model.of("tensile", 12.5f, true, geometry))
        assertEquals(StressStrain.Model.Axial(12.5f, true), StressStrain.Model.of("", 12.5f, true, geometry))
        // A session stored as a test this build no longer offers is not mistaken
        // for another test: it reads as no type, which is axial.
        assertEquals(StressStrain.Model.Axial(12.5f, true), StressStrain.Model.of("torsion", 12.5f, true, geometry))
        assertEquals("axial", StressStrain.Model.of("", 0f, true, SpecimenGeometry.NONE).wireName)
        assertEquals("flexural", StressStrain.Model.of("bending", 0f, true, geometry).wireName)
    }
}
