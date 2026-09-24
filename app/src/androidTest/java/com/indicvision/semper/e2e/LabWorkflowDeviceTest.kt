@file:Suppress("MagicNumber")

package com.indicvision.semper.e2e

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.google.android.material.button.MaterialButtonToggleGroup
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.data.BeamEdgeTaps
import com.indicvision.semper.data.CoachPrefs
import com.indicvision.semper.data.SessionPaths
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.SpecimenGeometry
import com.indicvision.semper.data.TestType
import com.indicvision.semper.report.BeamDeflection
import com.indicvision.semper.report.ElasticModulus
import com.indicvision.semper.report.StressStrain
import com.indicvision.semper.ui.home.SessionOpenHelper
import com.indicvision.semper.ui.viewer.LabReportExporter
import com.indicvision.semper.ui.viewer.ResultViewerActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The student lab on a device, from a saved session onwards: the numbers the
 * lab needs come off the `.dat` files as the phone writes them, the viewer
 * opens on Results, the Elastic region toggle redraws, the lab report PDF is
 * written, and the viewer's gestures (swipe to scrub, pinch, pan, tap to
 * probe) behave.
 *
 * Sessions are seeded on disk with a known field, as
 * [com.indicvision.semper.ui.viewer.ViewerEntryParityDeviceTest] does, so the
 * expected E is exact: tensile 200 GPa over frames 1–6, bending 3 GPa.
 * The wizard's own inputs (load card, taps) are covered by
 * `BeamTapEditorGestureTest` and `WizardDraftRestoreTest`.
 */
@RunWith(AndroidJUnit4::class)
class LabWorkflowDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun markCoachSeen() {
        CoachPrefs.Screen.entries.forEach { CoachPrefs.markSeen(context, it) }
    }

    @After
    fun remove() {
        SessionStore.delete(context, TENSILE_ID)
        SessionStore.delete(context, BENDING_ID)
    }

    // ------------------------------------------------------------------ tensile

    @Test
    fun aTensileSessionGivesTheModulusFromItsOwnFiles() {
        val record = seedTensile()
        val fit = ElasticModulus.fit(curveOf(record))

        assertNotNull("no straight leading run found", fit)
        assertEquals(200f, fit!!.modulusGPa, 0.5f)
        assertEquals(5, fit.lastFrame)
    }

    @Test
    fun aTensileSessionOpensOnResultsAndTheElasticToggleRedraws() {
        val record = seedTensile()
        ActivityScenario.launch<ResultViewerActivity>(SessionOpenHelper.intentFor(context, record)).use { scenario ->
            awaitOn(scenario, "Results") { it.findViewById<View>(R.id.tvSummaryResultsText).isVisible }
            var wholeCaption = ""
            scenario.onActivity { viewer ->
                assertTrue(viewer.findViewById<View>(R.id.summaryResults).isVisible)
                assertTrue(
                    "a tensile fit shows the range toggle",
                    viewer.findViewById<View>(R.id.summaryRangeClip).isVisible,
                )
                assertTrue(viewer.findViewById<TextView>(R.id.tvSummaryResultsText).text.isNotBlank())
                wholeCaption = viewer.findViewById<TextView>(R.id.tvSummaryResultsCaption).text.toString()
                viewer.findViewById<View>(R.id.btnRangeElastic).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { viewer ->
                val group = viewer.findViewById<MaterialButtonToggleGroup>(R.id.toggleSummaryRange)
                assertEquals(R.id.btnRangeElastic, group.checkedButtonId)
                val elasticCaption = viewer.findViewById<TextView>(R.id.tvSummaryResultsCaption).text.toString()
                assertFalse("the caption names the fit when zoomed", elasticCaption == wholeCaption)
                viewer.findViewById<View>(R.id.btnRangeWhole).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { viewer ->
                val group = viewer.findViewById<MaterialButtonToggleGroup>(R.id.toggleSummaryRange)
                assertEquals(R.id.btnRangeWhole, group.checkedButtonId)
                assertEquals(wholeCaption, viewer.findViewById<TextView>(R.id.tvSummaryResultsCaption).text.toString())
            }
        }
    }

    @Test
    fun aTensileLabReportIsAPdf() {
        assertIsPdf(labReport(seedTensile()))
    }

    // ------------------------------------------------------------------ bending

    @Test
    fun aBendingSessionGivesEFromTheDeflectionAtTheTaps() {
        val summary = BeamDeflection.summarize(curveOf(seedBending()))

        assertNotNull("no deflection read at the load point", summary)
        assertEquals(0.1f, summary!!.mmPerPx, 1e-4f)
        assertEquals(3f, summary.meanModulusGPa!!, 0.05f)
        assertEquals(3f, summary.slopeModulusGPa!!, 0.05f)
    }

    @Test
    fun aBendingSessionOpensOnResultsWithoutTheTensileToggle() {
        val record = seedBending()
        ActivityScenario.launch<ResultViewerActivity>(SessionOpenHelper.intentFor(context, record)).use { scenario ->
            awaitOn(scenario, "Results") { it.findViewById<View>(R.id.tvSummaryResultsText).isVisible }
            scenario.onActivity { viewer ->
                assertTrue(viewer.findViewById<TextView>(R.id.tvSummaryResultsText).text.isNotBlank())
                assertFalse(
                    "the Elastic region is tensile only",
                    viewer.findViewById<View>(R.id.summaryRangeClip).isVisible,
                )
            }
        }
    }

    @Test
    fun aBendingLabReportIsAPdf() {
        assertIsPdf(labReport(seedBending()))
    }

    // ------------------------------------------------------------------ gestures

    @Test
    fun theViewerScrubsZoomsPansAndProbes() {
        val record = seedTensile()
        val intent = SessionOpenHelper.argsFor(record).copy(startFrame = 0).toIntent(context)
        ActivityScenario.launch<ResultViewerActivity>(intent).use { scenario ->
            awaitOn(scenario, "frame 1's field") { it.rawData != null && it.imgMain.drawable != null }
            val restScale = scaleOf(scenario)

            // At rest, a horizontal swipe is a scrub: one frame whether it is a
            // flick (the fling detector) or a slow drag (the swipe distance).
            swipeAcrossImage(FLICK_STEPS)
            awaitOn(scenario, "the flick to reach frame 2") { it.currentFrameIndex >= 1 }
            device.waitForIdle()
            scenario.onActivity { assertEquals("one flick moved more than one frame", 1, it.currentFrameIndex) }
            swipeAcrossImage(SLOW_STEPS)
            awaitOn(scenario, "the slow swipe to reach frame 3") { it.currentFrameIndex >= 2 }
            device.waitForIdle()
            scenario.onActivity { assertEquals("one slow swipe moved more than one frame", 2, it.currentFrameIndex) }

            image().pinchOpen(PINCH_PERCENT)
            device.waitForIdle()
            val zoomed = scaleOf(scenario)
            assertTrue("pinch-open did not zoom ($restScale → $zoomed)", zoomed > restScale * 1.2f)

            // Zoomed in, the same swipe pans instead of scrubbing.
            swipeAcrossImage(PAN_STEPS)
            device.waitForIdle()
            scenario.onActivity { assertEquals("a pan changed the frame", 2, it.currentFrameIndex) }
            assertEquals(zoomed, scaleOf(scenario), zoomed * 0.01f)

            // A short tap reads the nearest point.
            val box = image().visibleBounds
            device.click(box.centerX(), box.centerY())
            awaitOn(scenario, "the probe readout") { it.tvProbeReadout.isVisible }

            image().pinchClose(PINCH_PERCENT)
            device.waitForIdle()
            assertTrue("pinch-close went below the rest scale", scaleOf(scenario) >= restScale * 0.98f)
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A leftward drag across the middle of the image, clear of the colour-scale
     * rail on the right edge and the controls at the bottom. UiDevice injects a
     * move every ~5 ms, so [steps] sets the speed: 10 is a flick, 500 stays
     * under TouchImageView's 400 px/s fling threshold.
     */
    private fun swipeAcrossImage(steps: Int) {
        val box = image().visibleBounds
        val y = box.centerY()
        val from = box.left + (box.width() * SWIPE_FROM).toInt()
        val to = box.left + (box.width() * SWIPE_TO).toInt()
        assertTrue("the swipe could not be injected", device.swipe(from, y, to, y, steps))
        device.waitForIdle()
    }

    private fun image() = checkNotNull(device.findObject(By.res(context.packageName, "imgBaseResult"))) {
        "imgBaseResult is not on screen"
    }

    private fun scaleOf(scenario: ActivityScenario<ResultViewerActivity>): Float {
        var scale = 0f
        scenario.onActivity {
            val values = FloatArray(9)
            it.imgMain.getZoomMatrix().getValues(values)
            scale = values[Matrix.MSCALE_X]
        }
        return scale
    }

    private fun awaitOn(
        scenario: ActivityScenario<ResultViewerActivity>,
        what: String,
        done: (ResultViewerActivity) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            var ok = false
            scenario.onActivity { ok = done(it) }
            if (ok) return
            check(System.currentTimeMillis() < deadline) {
                var frame = -1
                scenario.onActivity { frame = it.currentFrameIndex }
                "timed out waiting for $what (on frame index $frame)"
            }
            Thread.sleep(POLL_MS)
        }
    }

    private fun curveOf(record: SessionRecord): StressStrain.Curve {
        val dir = File(record.sessionDir)
        val model = StressStrain.Model.of(record.testType, record.crossSectionMm2, record.loadAxisX, record.geometry)
        return StressStrain.build(record.loadsN, model, { i -> DicResult.decodeDatFile(SessionPaths.frameDat(dir, i)) })
    }

    private fun labReport(record: SessionRecord): File {
        val dest = File(context.cacheDir, "lab_report_${record.id}.pdf").apply { delete() }
        runBlocking { LabReportExporter(context).write(curveOf(record), null, IMG_W to IMG_H, null, dest) }
        return dest
    }

    private fun assertIsPdf(file: File) {
        assertTrue("${file.name} was not written", file.isFile && file.length() > MIN_PDF_BYTES)
        val magic = ByteArray(PDF_MAGIC.length)
        file.inputStream().use { it.read(magic) }
        assertEquals(PDF_MAGIC, String(magic, Charsets.US_ASCII))
        file.delete()
    }

    /**
     * Six elastic frames at E = 200 GPa (σ = 200·ε, ε in mε), then two plastic
     * ones, the last the peak. 10 mm² cross-section.
     */
    private fun seedTensile(): SessionRecord {
        val strain = listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4.5f)
        val stress = strain.take(ELASTIC_FRAMES).map { it * 200f } + listOf(620f, 640f)
        return seed(
            id = TENSILE_ID,
            type = TestType.TENSILE,
            loadsN = stress.map { it * CROSS_SECTION_MM2 },
            crossSectionMm2 = CROSS_SECTION_MM2,
            geometry = SpecimenGeometry.NONE,
            exxMilli = strain,
            vPx = List(strain.size) { 0f },
        )
    }

    /**
     * Three-point bend, span 80, width 10, thickness 4 mm, taps 40 px apart
     * (0.1 mm/px). δ = W·L³ / (48·E·I) at E = 3 GPa is W / 15 mm, W / 1.5 px.
     */
    private fun seedBending(): SessionRecord {
        val loads = listOf(15f, 30f, 45f, 60f, 75f, 90f)
        return seed(
            id = BENDING_ID,
            type = TestType.BENDING,
            loadsN = loads,
            crossSectionMm2 = 0f,
            geometry = SpecimenGeometry(
                spanMm = 80f,
                widthMm = 10f,
                thicknessMm = 4f,
                loadPoint = BeamEdgeTaps(topX = 100f, topY = 40f, bottomX = 100f, bottomY = 80f),
            ),
            exxMilli = loads.map { it * 0.01f },
            vPx = loads.map { it / 1.5f },
        )
    }

    @Suppress("LongParameterList") // one argument per thing a session records about its test
    private fun seed(
        id: String,
        type: TestType,
        loadsN: List<Float>,
        crossSectionMm2: Float,
        geometry: SpecimenGeometry,
        exxMilli: List<Float>,
        vPx: List<Float>,
    ): SessionRecord {
        SessionStore.delete(context, id)
        val dir = SessionStore.dirFor(context, id)
        val ref = File(dir, "reference.png")
        Bitmap.createBitmap(IMG_W, IMG_H, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GRAY)
            ref.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val floats = FloatArray(COLS * ROWS * DicResult.STRIDE)
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        loadsN.indices.forEach { frame ->
            for (p in floats.indices step DicResult.STRIDE) {
                val point = p / DicResult.STRIDE
                floats[p + DicResult.IDX_X] = (ROI_X + point % COLS * STEP).toFloat()
                floats[p + DicResult.IDX_Y] = (ROI_Y + point / COLS * STEP).toFloat()
                floats[p + DicResult.IDX_U] = 0f
                floats[p + DicResult.IDX_V] = vPx[frame]
                floats[p + DicResult.IDX_EXX] = exxMilli[frame] / DicResult.STRAIN_TO_MILLISTRAIN
                floats[p + DicResult.IDX_EYY] = 0f
                floats[p + DicResult.IDX_EXY] = 0f
                floats[p + DicResult.IDX_ZNSSD] = 0.01f
            }
            buf.clear()
            buf.asFloatBuffer().put(floats)
            SessionPaths.frameDat(dir, frame).writeBytes(buf.array())
        }
        val now = System.currentTimeMillis()
        val record = SessionRecord(
            id = id,
            name = "Lab ${type.wireName}",
            createdAt = now,
            updatedAt = now,
            frameCount = loadsN.size,
            subset = SUBSET,
            step = STEP,
            strainWindow = STRAIN_WINDOW,
            imgW = IMG_W,
            imgH = IMG_H,
            roiX = ROI_X,
            roiY = ROI_Y,
            roiW = ROI_W,
            roiH = ROI_H,
            refPath = ref.absolutePath,
            refName = ref.name,
            sessionDir = dir.absolutePath,
            defNames = loadsN.indices.map { "f${it + 1}.png" },
            plannedFrameCount = loadsN.size,
            testType = type.wireName,
            crossSectionMm2 = crossSectionMm2,
            loadAxisX = true,
            geometry = geometry,
            loadsN = loadsN,
        )
        assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
        return record
    }

    private companion object {
        const val TENSILE_ID = "test_lab_tensile"
        const val BENDING_ID = "test_lab_bending"
        const val IMG_W = 200
        const val IMG_H = 120
        const val ROI_X = 20
        const val ROI_Y = 20
        const val ROI_W = 160
        const val ROI_H = 80
        const val STEP = 4
        const val COLS = ROI_W / STEP
        const val ROWS = ROI_H / STEP
        const val SUBSET = 21
        const val STRAIN_WINDOW = 21
        const val ELASTIC_FRAMES = 6
        const val CROSS_SECTION_MM2 = 10f
        const val MIN_PDF_BYTES = 1_000L
        const val PDF_MAGIC = "%PDF-"
        const val SWIPE_FROM = 0.7f
        const val SWIPE_TO = 0.2f
        const val FLICK_STEPS = 10
        const val PAN_STEPS = 40
        const val SLOW_STEPS = 500
        const val PINCH_PERCENT = 0.8f
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 50L
    }
}
