package com.sempermechanics.semper.ui.analysis.frames

import com.sempermechanics.semper.field.ImageSize

/**
 * One deformed frame the wizard holds: its staged [path], the [name] the user
 * picked it as, its best-effort capture [date] ([UNKNOWN_DATE] when unknown,
 * which sorts last), and its pixel [size] (null until measured).
 *
 * A batch is a `List<DeformedFrame>` in run order, so a frame's name, date,
 * size and lab inputs (its time and its typed load) move with it whenever the
 * list is reordered. The wizard's draft
 * (`WizardState.Frames`) and the view model's read-only list views keep the
 * older parallel-list shape; [unzip] makes it.
 */
data class DeformedFrame(
    val path: String,
    val name: String,
    val date: Long = UNKNOWN_DATE,
    val size: ImageSize? = null,
    /** Lab: ms after the reference in the clip this frame was sampled from; null for a picked photo. */
    val videoTimeMs: Long? = null,
    /** Lab: the photo's EXIF capture time (`PhotoCaptureTime`); null for a video frame or a photo without one. */
    val captureTimeMs: Long? = null,
    /** Lab, bending: the hanger mass typed for this photo, kg; null while its box is blank. */
    val typedLoadKg: Float? = null,
) {

    /** The four parallel fields of a batch, in the view model's shapes. */
    data class Lists(
        val paths: List<String>,
        val names: List<String>,
        val dates: List<Long>,
        val sizes: Map<String, Pair<Int, Int>>,
    )

    companion object {
        /** `defFrameDates`' marker for a frame whose date is unknown. */
        const val UNKNOWN_DATE = Long.MAX_VALUE

        /** The four parallel fields of [frames]; frames with no size are left out of the map. */
        fun unzip(frames: List<DeformedFrame>): Lists = Lists(
            paths = frames.map { it.path },
            names = frames.map { it.name },
            dates = frames.map { it.date },
            sizes = frames.mapNotNull { f -> f.size?.let { f.path to it.toPair() } }.toMap(),
        )
    }
}
