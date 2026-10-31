package com.sempermechanics.semper.pipeline

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.ProgressCallback
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.data.examples.ExampleAssetProvider
import com.sempermechanics.semper.data.examples.ExampleCatalog
import com.sempermechanics.semper.data.examples.ExampleManifest
import com.sempermechanics.semper.field.DicResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The bundled example sets on a real build (ADR-031): every file each set
 * lists opens through [ExampleAssetProvider] as Try it would open it (a
 * compressed asset would fail here, not on the JVM), and the two DICe pairs,
 * decoded by the engine's own imgcodecs as the wizard hands them over, give
 * DICe's answers.
 */
@RunWith(AndroidJUnit4::class)
class ExampleDatasetsDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun set(id: String): ExampleManifest = requireNotNull(ExampleCatalog.find(context.assets, id)) { id }

    private fun bytes(set: ExampleManifest, file: String): ByteArray {
        val uri = ExampleAssetProvider.uriFor(context, set.id, file)
        return requireNotNull(context.contentResolver.openInputStream(uri)) { "$uri" }.use { it.readBytes() }
    }

    @Test
    fun everyListedFileOpensThroughTheProvider() {
        val sets = ExampleCatalog.load(context.assets)
        assertEquals(4, sets.size)
        sets.forEach { set ->
            (listOf(set.reference) + set.deformed + listOfNotNull(set.loadLog?.file)).forEach { file ->
                val uri = ExampleAssetProvider.uriFor(context, set.id, file)
                context.contentResolver.openAssetFileDescriptor(uri, "r").use { fd ->
                    assertTrue("$uri is empty", requireNotNull(fd) { "$uri" }.length > 0)
                }
            }
        }
    }

    /** Medians of u and v over the accepted points, and the share accepted. */
    private class Field(val medianU: Float, val medianV: Float, val accepted: Float)

    private fun solve(set: ExampleManifest, step: Int, subset: Int): Field {
        val ref = bytes(set, set.reference)
        val def = bytes(set, set.deformed.single())
        val dims = SemperNativeLib.getImageDimensions(ref)
        assertEquals("${set.id} decoded size", set.imageSize, listOf(dims[0], dims[1]))
        SemperNativeLib.initializeReference(ref, null, dims[0], dims[1])
        val roi = set.roiRect
        val maxPoints = (roi.w / step + 1) * (roi.h / step + 1)
        val buffer = ByteBuffer.allocateDirect(maxPoints * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        val metrics = FloatArray(METRICS) { if (it == METRICS - 1) -1f else 0f }
        val callback = object : ProgressCallback {
            override fun onProgressUpdate(percentage: Int) {}
        }
        val points = SemperNativeLib.computeFullFieldDirect(
            ref, def, ByteArray(0),
            roi.x, roi.y, roi.w, roi.h,
            step, subset, (STRAIN_POINTS - 1) * step + 1,
            false,
            buffer, callback, metrics,
        )
        assertTrue("${set.id}: engine returned $points", points > 0)
        val data = FloatArray(points * DicResult.STRIDE)
        buffer.position(0)
        buffer.asFloatBuffer().get(data)
        val u = ArrayList<Float>()
        val v = ArrayList<Float>()
        for (i in data.indices step DicResult.STRIDE) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                u += data[i + DicResult.IDX_U]
                v += data[i + DicResult.IDX_V]
            }
        }
        assertTrue("${set.id}: no accepted points", u.isNotEmpty())
        u.sort()
        v.sort()
        return Field(u[u.size / 2], v[v.size / 2], u.size / points.toFloat())
    }

    @Test
    fun translationPairGivesDicesShift() {
        val field = solve(set("dice_translation"), step = 8, subset = 27)
        assertTrue("accepted ${field.accepted}", field.accepted >= MIN_ACCEPTED)
        // DICe's own test: |u − 0.4| ≤ 0.1 px; v moved the same way.
        assertTrue("median u ${field.medianU}", abs(field.medianU - SHIFT_PX) <= SHIFT_TOL_PX)
        assertTrue("median v ${field.medianV}", abs(field.medianV - SHIFT_PX) <= SHIFT_TOL_PX)
    }

    @Test
    fun openHolePairMatchesDicesSolution() {
        val field = solve(set("dice_oht_cfrp"), step = 10, subset = 27)
        assertTrue("accepted ${field.accepted}", field.accepted >= MIN_ACCEPTED)
        // DICe_solution_01.txt: median u −0.096 px, median v −0.627 px over its 230 points.
        assertTrue("median u ${field.medianU}", abs(field.medianU - OHT_U_PX) <= OHT_TOL_PX)
        assertTrue("median v ${field.medianV}", abs(field.medianV - OHT_V_PX) <= OHT_TOL_PX)
    }

    private companion object {
        const val METRICS = 17

        // The window is in pixels, (points − 1) × step + 1 as the app sizes it: the VSG fit needs 3 nodes in it.
        const val STRAIN_POINTS = 5
        const val MIN_ACCEPTED = 0.5f
        const val SHIFT_PX = 0.4f
        const val SHIFT_TOL_PX = 0.1f
        const val OHT_U_PX = -0.096f
        const val OHT_V_PX = -0.627f
        const val OHT_TOL_PX = 0.1f
    }
}
