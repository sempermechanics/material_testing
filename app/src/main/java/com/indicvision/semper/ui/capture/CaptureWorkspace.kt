package com.indicvision.semper.ui.capture

import java.io.File
import java.util.Locale

/**
 * Clears the frames a previous run left in the shared capture directory.
 *
 * Every run writes `frame_0000.png` upward into the same folder, so a shorter
 * run leaves the tail of a longer earlier one behind — and those leftovers are
 * not merely stale, they can be a different resolution and a different pixel
 * format entirely (an 11:41 run of 75 grayscale 3264×2448 frames was found
 * sitting on top of 36 RGB 3072×4080 frames from 11:03). Anything that lists
 * the directory rather than the run's own file list then mixes two experiments
 * into one dataset, silently.
 *
 * The test shot is deliberately spared: it is captured before the run and is
 * still needed as the speckle-check source and the fallback reference.
 */
internal object CaptureWorkspace {

    /** The locked reference every deformed frame is correlated against. */
    const val REFERENCE_NAME = "reference.png"

    /** Discarded calibration frame; see CaptureSessionActivity's warm-up. */
    const val WARMUP_NAME = "warmup.png"

    /** Vendor Camera app shot used for the speckle check. Never a run output. */
    const val TEST_SHOT_NAME = "test.jpg"

    private const val FRAME_PREFIX = "frame_"
    private const val FRAME_FORMAT = FRAME_PREFIX + "%04d.png"

    /** Deformed frame [index]'s filename. Locale-fixed: the default locale can
     *  render digits non-ASCII, which would not match [RUN_OUTPUT] and would
     *  leave the frames behind for the next run to mix in. */
    fun frameName(index: Int): String = String.format(Locale.US, FRAME_FORMAT, index)

    /** Derived from the names above rather than restated, so a rename cannot
     *  leave the sweep matching the old one. */
    private val RUN_OUTPUT = Regex(
        "^(" + Regex.escape(FRAME_PREFIX) + """\d+\.(png|jpg)|""" +
            Regex.escape(REFERENCE_NAME) + "|" + Regex.escape(WARMUP_NAME) + ")$",
    )

    /** Deletes prior run output from [dir]. Returns how many files went. */
    fun clearPreviousRun(dir: File): Int {
        val stale = dir.listFiles()?.filter { it.isFile && RUN_OUTPUT.matches(it.name) }.orEmpty()
        return stale.count { it.delete() }
    }
}
