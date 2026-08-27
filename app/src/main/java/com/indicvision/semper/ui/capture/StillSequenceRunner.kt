package com.indicvision.semper.ui.capture

/**
 * Schedules when each locked still should fire. Pure clock logic — the
 * capture sink is injected so JVM tests need no Camera2.
 *
 * Late frames are still captured (never skipped). Empty captures retry the
 * same index until [CaptureSink.capture] returns a non-null path, the caller
 * cancels, or the frame exhausts [maxAttemptsPerFrame] — at which point the
 * run ends incomplete, keeping the frames already taken.
 */
class StillSequenceRunner(
    /**
     * Gap between frame start times. An explicit interval rather than an fps:
     * integer fps cannot express slower than one frame a second, and a run
     * whose frame count is capped below fps × duration needs exactly that —
     * 30 frames across a 120s test is one every 4s. Deriving the interval
     * from fps would instead bunch them into the first 10s and miss the rest
     * of the deformation.
     */
    private val intervalMs: Long,
    private val frameCount: Int,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val delayMs: suspend (Long) -> Unit = { ms -> kotlinx.coroutines.delay(ms) },
    /**
     * Attempts allowed on a single frame before the run is abandoned.
     * Retrying forever is only correct while failures are transient; a
     * permanent one (no space left, a vendor encode quirk) would otherwise
     * spin as fast as the CPU allows with nothing shown to the user.
     */
    private val maxAttemptsPerFrame: Int = DEFAULT_MAX_ATTEMPTS,
) {
    interface CaptureSink {
        /**
         * Take one still for [index]. Return a non-empty file path on success,
         * or null to retry the same instant.
         */
        suspend fun capture(index: Int): String?
    }

    data class Result(
        val paths: List<String>,
        val completed: Boolean,
    )

    /**
     * Captures [frameCount] stills starting at [t0Ms] (defaults to now).
     * Invokes [onProgress] after each successful frame (1-based done count).
     */
    @Suppress("ReturnCount")
    suspend fun run(
        sink: CaptureSink,
        t0Ms: Long = clockMs(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
    ): Result {
        val paths = ArrayList<String>(frameCount)
        for (i in 0 until frameCount) {
            if (!isActive()) return Result(paths, completed = false)
            val wait = dueAtMs(t0Ms, i) - clockMs()
            if (wait > 0) delayMs(wait)

            var path: String? = null
            var attempts = 0
            while (path == null) {
                if (!isActive()) return Result(paths, completed = false)
                path = sink.capture(i)
                if (path == null && ++attempts >= maxAttemptsPerFrame) {
                    return Result(paths, completed = false)
                }
            }
            paths.add(path)
            onProgress(paths.size, frameCount)
        }
        return Result(paths, completed = true)
    }

    /**
     * When frame [index] of a run started at [t0Ms] should fire.
     *
     * Every frame is scheduled from t0 rather than from the previous capture,
     * so a slow frame does not push the whole run late: the next one is simply
     * due sooner, and the last still lands at the end of the test window.
     */
    fun dueAtMs(t0Ms: Long, index: Int): Long = t0Ms + index * intervalMs.coerceAtLeast(1L)

    companion object {
        /**
         * Generous enough that a run survives a handful of dropped frames
         * (a transient buffer starve, one slow write), small enough that a
         * permanent failure surfaces in seconds rather than never.
         */
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}
