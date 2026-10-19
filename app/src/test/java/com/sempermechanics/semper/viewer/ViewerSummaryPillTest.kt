package com.sempermechanics.semper.viewer

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.launchViewer
import com.sempermechanics.semper.fixtures.viewerArgs
import com.sempermechanics.semper.fixtures.writeGridBatch
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * [com.sempermechanics.semper.ui.viewer.summary.ViewerSummaryController]'s
 * progress pill above the scrubber, and the colour scale's ends while the
 * summary does not know its range yet.
 *
 * Native graphics: the loop's GIF must decode (ImageDecoder) for the pill to go;
 * under legacy graphics the decode fails and the pill says so instead.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class ViewerSummaryPillTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private companion object {
        const val FRAMES = 3
        const val GRID = 4
        const val STEP = 4
        const val DASH = "—"

        /** Past the chrome's 2.5 s auto-hide and its fade. */
        val PAST_AUTO_HIDE: Duration = Duration.ofMillis(3_500)
    }

    @Before
    fun writeBatch() {
        batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, FRAMES, GRID, STEP)
    }

    /** On frame 1, with the sequence range already known. */
    private fun viewerWithRanges(): ResultViewerActivity =
        launchViewer(viewerArgs(batchDir, GRID, STEP)).also { activity ->
            idleUntil("the viewer and the summary's ranges") {
                activity.frameSetLoaded &&
                    activity.rawData != null &&
                    activity.summary.boundsFor(activity.currentDataIndex) != null
            }
        }

    private val ResultViewerActivity.pill: View get() = findViewById(R.id.summaryStatus)
    private val ResultViewerActivity.pillText: String
        get() = findViewById<TextView>(R.id.tvSummaryStatus).text.toString()
    private val ResultViewerActivity.scaleMax: String
        get() = findViewById<TextView>(R.id.tvScaleMax).text.toString()
    private val ResultViewerActivity.scaleMin: String
        get() = findViewById<TextView>(R.id.tvScaleMin).text.toString()

    /** Prev from frame 1 enters the summary; the build is launched but has not run. */
    private fun ResultViewerActivity.enterSummary() {
        findViewById<View>(R.id.btnPrevFrame).performClick()
        assertTrue(isShowingSummary)
    }

    @Test
    fun `the pill shows the build's progress and a close button, then goes once the loop plays`() {
        val activity = viewerWithRanges()
        assertEquals(View.GONE, activity.pill.visibility)

        activity.enterSummary()

        assertEquals(View.VISIBLE, activity.pill.visibility)
        assertEquals(activity.getString(R.string.summary_building_fmt, 0, FRAMES), activity.pillText)
        assertEquals("Building summary · 0 of 3", activity.pillText)
        val close = activity.findViewById<View>(R.id.btnSummaryCancel)
        assertEquals(View.VISIBLE, close.visibility)
        assertEquals(activity.getString(R.string.summary_cancel_desc), close.contentDescription)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.progressSummary).visibility)
        assertEquals(
            "the status line is a polite live region",
            View.ACCESSIBILITY_LIVE_REGION_POLITE,
            activity.findViewById<View>(R.id.tvSummaryStatus).accessibilityLiveRegion,
        )
        // The range is known, so the ends are the animation's, not "—".
        assertTrue(activity.scaleMax, activity.scaleMax.startsWith("≥"))
        assertTrue(activity.scaleMin, activity.scaleMin.startsWith("≤"))

        idleUntil("the summary loop") { activity.pill.visibility == View.GONE }
        assertTrue(
            "the animation is on screen",
            activity.findViewById<ImageView>(R.id.imgSummary).drawable != null,
        )
    }

    @Test
    fun `the close button cancels the build and drops the pill`() {
        val activity = viewerWithRanges()
        activity.enterSummary()
        assertEquals(View.VISIBLE, activity.pill.visibility)

        activity.findViewById<View>(R.id.btnSummaryCancel).performClick()
        assertEquals(View.GONE, activity.pill.visibility)

        // Past the auto-hide too: nothing the cancelled build posts brings it back.
        shadowOf(activity.mainLooper).idleFor(PAST_AUTO_HIDE)
        assertEquals(View.GONE, activity.pill.visibility)
        assertNull("the cancelled build drew nothing", activity.findViewById<ImageView>(R.id.imgSummary).drawable)
    }

    @Test
    fun `leaving the summary hides the pill`() {
        val activity = viewerWithRanges()
        activity.enterSummary()

        activity.findViewById<View>(R.id.btnNextFrame).performClick()

        assertFalse(activity.isShowingSummary)
        assertEquals(View.GONE, activity.pill.visibility)
    }

    @Test
    fun `the scale reads a dash at both ends until the summary knows its range`() {
        // One frame: the viewer does not start the range pass on open (only a
        // multi-frame batch does), so entering the summary starts it, and its
        // result can only land in a later main-thread task than the click.
        val single = temp.newFolder("single")
        writeGridBatch(single, 1, GRID, STEP)
        val activity = launchViewer(viewerArgs(single, GRID, STEP))
        idleUntil("the frame's heatmap") { activity.frameSetLoaded && activity.scale.cachedHeatmap != null }
        assertTrue("the frame labels its own ends", activity.scaleMax.startsWith("≥"))
        assertNull(activity.summary.boundsFor(activity.currentDataIndex))

        activity.enterSummary()

        assertEquals("not the frame's ends, nor a fake 0.0", DASH, activity.scaleMax)
        assertEquals(DASH, activity.scaleMin)
        assertEquals("the pill is up while it reads frames", View.VISIBLE, activity.pill.visibility)
        assertEquals(activity.getString(R.string.summary_scanning_fmt, 0, 1), activity.pillText)
        assertEquals("Reading frames · 0 of 1", activity.pillText)

        idleUntil("the summary's ranges") { activity.summary.boundsFor(activity.currentDataIndex) != null }
        shadowOf(activity.mainLooper).idle()
        assertTrue(activity.scaleMax, activity.scaleMax.startsWith("≥"))
        assertTrue(activity.scaleMin, activity.scaleMin.startsWith("≤"))
    }
}
