package com.sempermechanics.semper.data.mechanical

/**
 * When a photo was taken: its EXIF `DateTimeOriginal` as [ms] on the camera's
 * clock, and whether `SubSecTimeOriginal` gave it [subSeconds]. Without them
 * the time is truncated to the second, so it is good only to ±1 s.
 *
 * [subSeconds] is null when it was never recorded: a wizard draft saved
 * before the flag. Such a batch is judged by its values, as it was then.
 */
data class CaptureStamp(val ms: Long, val subSeconds: Boolean? = null) {

    companion object {
        private const val MS_PER_S = 1000L

        /**
         * Each frame's time after the reference, in ms, or empty (no time match)
         * unless the reference and every frame have a capture time.
         */
        fun relativeTimesMs(reference: CaptureStamp?, frames: List<CaptureStamp?>): List<Long> {
            val stamps = frames.filterNotNull()
            if (reference == null || stamps.isEmpty() || stamps.size != frames.size) return emptyList()
            return stamps.map { it.ms - reference.ms }
        }

        /**
         * True when the batch is timed ([relativeTimesMs] is not empty) and any
         * of its times, the reference's or a frame's, was read without
         * sub-seconds: every frame's time after the reference is then good only
         * to ±1 s. A time with sub-seconds that lands on .000 is still precise.
         *
         * Times whose precision is unknown fall back to the old reading: the
         * batch is coarse when every time is a whole second, since a camera
         * that writes sub-seconds lands on .000 one time in a thousand.
         */
        fun coarse(reference: CaptureStamp?, frames: List<CaptureStamp?>): Boolean {
            if (reference == null || relativeTimesMs(reference, frames).isEmpty()) return false
            val all = frames.filterNotNull() + reference
            return all.any { it.subSeconds == false } ||
                (all.any { it.subSeconds == null } && all.all { it.ms % MS_PER_S == 0L })
        }
    }
}
