package com.indicvision.semper.ui.capture

import java.util.Locale

/**
 * mm:ss parsing and formatting for the test-duration field.
 *
 * A slider was the wrong control here. Test durations are chosen from the
 * experiment — "run it for four minutes" — not dialled in by feel, and a
 * slider spanning ten minutes cannot land on a specific second at all. A typed
 * field can, and mm:ss is how a duration that long is spoken.
 *
 * Parsing is deliberately forgiving of what someone actually types mid-edit
 * ("4", "4:", "4:5") and strict about the result: anything outside
 * [MIN_SECONDS]..[MAX_SECONDS] is clamped rather than refused, so the field
 * cannot hold a value the rest of the screen would have to defend against.
 */
internal object CaptureDurationText {

    const val MIN_SECONDS = 1

    /**
     * Ten minutes. Past this a locked run stops being a bench test: the camera
     * is held open with AE/AWB/AF frozen the whole time, the screen is kept
     * awake, and every frame is retained for analysis — all of which get worse
     * linearly with no natural stopping point. The frame ceiling
     * ([com.indicvision.semper.data.DicSettings.MAX_MAX_FRAMES]) bounds the
     * count; this bounds the wall time.
     */
    const val MAX_SECONDS = 600

    private const val SECONDS_PER_MINUTE = 60

    /** Canonical "mm:ss" for [totalSeconds], clamped into range. */
    fun format(totalSeconds: Int): String {
        val clamped = clamp(totalSeconds)
        return String.format(
            Locale.US,
            "%02d:%02d",
            clamped / SECONDS_PER_MINUTE,
            clamped % SECONDS_PER_MINUTE,
        )
    }

    /**
     * Seconds from [text], or null when it is not yet a duration.
     *
     * Null means "still typing", not "invalid" — the caller leaves the last
     * good value in place rather than snapping the screen to a default while
     * the user is mid-keystroke.
     */
    fun parse(text: CharSequence?): Int? {
        val raw = text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split(':')
        return when (parts.size) {
            1 -> parts[0].toIntOrNull()?.let { clamp(it) }
            2 -> twoPart(parts[0], parts[1])
            else -> null
        }
    }

    private fun twoPart(minutesText: String, secondsText: String): Int? {
        // An empty half is a half-typed duration, not a bad one: "4:" is on the
        // way to four minutes, ":30" on the way to thirty seconds.
        val minutes = if (minutesText.isEmpty()) 0 else minutesText.toIntOrNull()
        val seconds = if (secondsText.isEmpty()) 0 else secondsText.toIntOrNull()
        // Out-of-range halves need no guard of their own: clamp is the one
        // place the bounds live, and it catches whatever the sum comes to.
        return if (minutes != null && seconds != null) {
            clamp(minutes * SECONDS_PER_MINUTE + seconds)
        } else {
            null
        }
    }

    fun clamp(totalSeconds: Int): Int = totalSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
}
