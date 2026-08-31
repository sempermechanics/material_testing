package com.indicvision.semper.ui.capture

import android.graphics.Bitmap
import android.graphics.PointF
import android.view.TextureView
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import com.indicvision.semper.R
import kotlin.math.roundToInt

/**
 * A magnified live crop of the preview at the focus point, and a reading of how
 * sharp it is against the sharpest point tried so far.
 *
 * The confirm step asks the user whether the speckle is sharp, and at preview
 * scale on a phone screen that is close to unanswerable: a speckle pattern is
 * fine repeating texture, which is exactly the kind of detail a 1× view of a
 * downscaled preview buffer cannot resolve. So the crop is drawn **unfiltered**
 * — real pixels, magnified, not smoothed into something that looks sharp because
 * the scaler made it so.
 *
 * **The number is a comparison and is worded as one.** It is a percentage of the
 * best reading seen since the step opened, never a verdict, because
 * [FocusSharpness] has no absolute scale — how much gradient a speckle pattern
 * *should* have depends on the pattern. What it can say honestly is that this tap
 * is softer than one already tried, which is the decision in front of the user.
 *
 * Everything is reused across samples: one frame grab, one window, one pixel
 * array. A view-sized bitmap allocated a few times a second would churn tens of
 * megabytes through the collector while the camera is streaming.
 */
internal class FocusLoupe(
    private val preview: TextureView,
    private val loupe: ImageView,
    private val reading: TextView,
) {

    private var frame: Bitmap? = null
    private var window: Bitmap? = null
    private var pixels: IntArray? = null
    private var best = 0.0

    /** Forget the best seen, so a new confirm step compares within itself. */
    fun reset() {
        best = 0.0
    }

    fun hide() {
        loupe.isVisible = false
        reading.isVisible = false
    }

    /**
     * Take one sample at [viewPoint] and show it.
     *
     * Silently does nothing when there is no point, no preview surface yet, or
     * the view is smaller than one window — all transient states around a lock,
     * and none of them worth a message.
     */
    fun update(viewPoint: PointF?) {
        val point = viewPoint ?: return
        val grabbed = grab() ?: return
        val edge = WINDOW_PX
        val left = (point.x.roundToInt() - edge / 2).coerceIn(0, grabbed.width - edge)
        val top = (point.y.roundToInt() - edge / 2).coerceIn(0, grabbed.height - edge)
        val buffer = pixelBuffer(edge)
        grabbed.getPixels(buffer, 0, edge, left, top, edge, edge)
        show(buffer, edge)
    }

    private fun show(buffer: IntArray, edge: Int) {
        val target = windowBitmap(edge)
        target.setPixels(buffer, 0, edge, 0, 0, edge, edge)
        loupe.invalidate()
        loupe.isVisible = true

        val score = FocusSharpness.of(buffer, edge, edge)
        if (score > best) best = score
        reading.text = when {
            best <= 0.0 || score >= best -> reading.context.getString(R.string.capture_focus_sharpest_yet)
            else -> reading.context.getString(
                R.string.capture_focus_sharpness_fmt,
                (PERCENT * score / best).roundToInt(),
            )
        }
        reading.isVisible = true
    }

    /**
     * The preview's current pixels at view resolution.
     *
     * At view resolution because that is where the detail is: the preview buffer
     * is capped well below the capture size already, and asking [TextureView] for
     * a smaller bitmap makes it *scale the whole view down*, which would smooth
     * away the very high frequencies being measured — a soft focus would read as
     * sharp as a good one.
     */
    private fun grab(): Bitmap? {
        val w = preview.width
        val h = preview.height
        if (!preview.isAvailable || minOf(w, h) < WINDOW_PX) return null
        val existing = frame
        val target = if (existing != null && existing.width == w && existing.height == h) {
            existing
        } else {
            existing?.recycle()
            createBitmap(w, h).also { frame = it }
        }
        return runCatching { preview.getBitmap(target) }.getOrNull()
    }

    private fun pixelBuffer(edge: Int): IntArray {
        val existing = pixels
        return if (existing != null && existing.size == edge * edge) {
            existing
        } else {
            IntArray(edge * edge).also { pixels = it }
        }
    }

    private fun windowBitmap(edge: Int): Bitmap {
        val existing = window
        if (existing != null && existing.width == edge) return existing
        existing?.recycle()
        val created = createBitmap(edge, edge)
        window = created
        // Set once, and unfiltered: the magnification is the whole point, and a
        // filtered upscale would blur exactly the difference being judged.
        loupe.setImageDrawable(
            created.toDrawable(loupe.resources).apply { isFilterBitmap = false },
        )
        return created
    }

    /** Frees the two view-sized allocations; the loupe rebuilds them on demand. */
    fun release() {
        loupe.setImageDrawable(null)
        frame?.recycle()
        frame = null
        window?.recycle()
        window = null
        pixels = null
    }

    private companion object {
        const val PERCENT = 100.0

        /**
         * Side of the sampled window, in real preview pixels.
         *
         * A pixel count rather than a dimension: it is how much of the *image*
         * is examined, which must not change with screen density. The loupe's
         * drawn size does come from resources, so the magnification is the ratio
         * between the two.
         */
        const val WINDOW_PX = 96
    }
}
