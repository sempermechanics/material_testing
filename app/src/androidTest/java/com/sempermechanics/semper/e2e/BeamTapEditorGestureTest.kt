@file:Suppress("MagicNumber")

package com.indicvision.semper.e2e

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.view.View
import android.view.ViewConfiguration
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.data.BeamEdgeTaps
import com.indicvision.semper.ui.analysis.BeamEdgeTapActivity
import com.indicvision.semper.ui.viewer.TouchImageView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * The bending tap editor under real touches: a tap on the top edge and one on
 * the bottom edge give the taps it saves, the bottom mark is held to the top
 * mark's x however far the finger slips sideways (`BeamTapPlacement`), and a
 * pinch-zoom survives the next tap (the photo stays still between taps).
 */
@RunWith(AndroidJUnit4::class)
class BeamTapEditorGestureTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var photo: File

    @Before
    fun writePhoto() {
        photo = File(context.cacheDir, "beam_tap_test.png")
        val random = Random(SEED)
        val pixels = IntArray(IMG_W * IMG_H) {
            val grey = random.nextInt(256)
            Color.rgb(grey, grey, grey)
        }
        Bitmap.createBitmap(pixels, IMG_W, IMG_H, Bitmap.Config.ARGB_8888).apply {
            photo.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
    }

    @After
    fun removePhoto() {
        photo.delete()
    }

    private fun launch(): ActivityScenario<BeamEdgeTapActivity> {
        val intent = Intent(context, BeamEdgeTapActivity::class.java)
            .putExtra(DicKeys.IMAGE_FILE_PATH, photo.absolutePath)
            .putExtra(DicKeys.IMAGE_WIDTH, IMG_W)
            .putExtra(DicKeys.IMAGE_HEIGHT, IMG_H)
            .putExtra(DicKeys.BEAM_THICKNESS_MM, THICKNESS_MM)
        val scenario = ActivityScenario.launchActivityForResult<BeamEdgeTapActivity>(intent)
        awaitOn(scenario, "the photo") { photoView(it).drawable != null }
        return scenario
    }

    @Test
    fun theBottomTapIsHeldToTheTopTapsX() {
        launch().use { scenario ->
            tapImage(scenario, TOP_X, TOP_Y)
            tapImage(scenario, TOP_X + SLIP_PX, BOTTOM_Y)
            val taps = save(scenario)

            assertEquals(TOP_X, taps.topX, TOLERANCE_PX)
            assertEquals(TOP_Y, taps.topY, TOLERANCE_PX)
            assertEquals("the bottom mark left the top mark's x", taps.topX, taps.bottomX, 0.01f)
            assertEquals(BOTTOM_Y, taps.bottomY, TOLERANCE_PX)
        }
    }

    @Test
    fun aPinchZoomSurvivesTheNextTap() {
        launch().use { scenario ->
            val rest = scaleOf(scenario)
            tapImage(scenario, TOP_X, TOP_Y)

            val view = checkNotNull(device.findObject(By.res(context.packageName, "imgBeamPhoto")))
            view.pinchOpen(PINCH_PERCENT)
            device.waitForIdle()
            val zoomed = scaleOf(scenario)
            assertTrue("pinch-open did not zoom ($rest → $zoomed)", zoomed > rest * 1.2f)

            // The instruction changes after the second tap; the photo must not re-fit.
            tapImage(scenario, TOP_X, BOTTOM_Y)
            assertEquals("the zoom was thrown away by a tap", zoomed, scaleOf(scenario), zoomed * 0.01f)

            val taps = save(scenario)
            assertEquals(BOTTOM_Y, taps.bottomY, TOLERANCE_PX)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun photoView(activity: Activity): TouchImageView = activity.findViewById(R.id.imgBeamPhoto)

    /**
     * Taps the screen where image pixel ([x], [y]) is drawn now, then waits out a double tap.
     *
     * Waits for the app to go idle first. On a cold API 37 emulator the main thread was
     * still busy after the photo appeared, the first click's up landed ~500 ms after its
     * down, and `GestureDetector` took it for a long press rather than a tap.
     */
    private fun tapImage(scenario: ActivityScenario<BeamEdgeTapActivity>, x: Float, y: Float) {
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        val onScreen = IntArray(2)
        val pts = floatArrayOf(x, y)
        scenario.onActivity {
            val view = photoView(it)
            view.getZoomMatrix().mapPoints(pts)
            view.getLocationOnScreen(onScreen)
        }
        device.click((onScreen[0] + pts[0]).toInt(), (onScreen[1] + pts[1]).toInt())
        // A second tap inside the double-tap window is a zoom toggle, not a mark.
        Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + SETTLE_MS)
        device.waitForIdle()
    }

    private fun scaleOf(scenario: ActivityScenario<BeamEdgeTapActivity>): Float {
        var scale = 0f
        scenario.onActivity {
            val values = FloatArray(9)
            photoView(it).getZoomMatrix().getValues(values)
            scale = values[Matrix.MSCALE_X]
        }
        return scale
    }

    private fun save(scenario: ActivityScenario<BeamEdgeTapActivity>): BeamEdgeTaps {
        scenario.onActivity {
            val save = it.findViewById<View>(R.id.btnBeamSave)
            assertTrue("Save is not enabled after two taps", save.isEnabled)
            save.performClick()
        }
        val result = scenario.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        return BeamEdgeTaps.fromArray(result.resultData.getFloatArrayExtra(DicKeys.BEAM_EDGE_TAPS))
    }

    private fun awaitOn(
        scenario: ActivityScenario<BeamEdgeTapActivity>,
        what: String,
        done: (BeamEdgeTapActivity) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            var ok = false
            scenario.onActivity { ok = done(it) }
            if (ok) return
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val IMG_W = 400
        const val IMG_H = 240
        const val THICKNESS_MM = 4f
        const val TOP_X = 200f
        const val TOP_Y = 80f
        const val BOTTOM_Y = 160f
        const val SLIP_PX = 40f

        // One screen pixel is under one image pixel at these sizes; allow for rounding.
        const val TOLERANCE_PX = 3f
        const val PINCH_PERCENT = 0.8f
        const val SEED = 7
        const val SETTLE_MS = 200L
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 50L
    }
}
