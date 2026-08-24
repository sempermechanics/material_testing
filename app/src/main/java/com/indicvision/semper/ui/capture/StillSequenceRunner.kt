package com.indicvision.semper.ui.capture

/**
 * Schedules when each locked still should fire. Pure clock logic — the
 * capture sink is injected so JVM tests need no Camera2.
 *
 * Late frames are still captured (never skipped). Empty captures retry the
 * same index until [CaptureSink.capture] returns a non-null path or the
 * caller cancels.
 */
class StillSequenceRunner(
    private val fps: Int,
    private val frameCount: Int,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val delayMs: suspend (Long) -> Unit = { ms -> kotlinx.coroutines.delay(ms) },
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
    suspend fun run(
        sink: CaptureSink,
        t0Ms: Long = clockMs(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
    ): Result {
        val intervalMs = (1000.0 / fps.coerceAtLeast(1)).toLong().coerceAtLeast(1L)
        val paths = ArrayList<String>(frameCount)
        for (i in 0 until frameCount) {
            if (!isActive()) return Result(paths, completed = false)
            val dueAt = t0Ms + i * intervalMs
            val wait = dueAt - clockMs()
            if (wait > 0) delayMs(wait)

            var path: String? = null
            while (path == null) {
                if (!isActive()) return Result(paths, completed = false)
                path = sink.capture(i)
            }
            paths.add(path)
            onProgress(paths.size, frameCount)
        }
        return Result(paths, completed = true)
    }

    /** Next due time for frame [index] given sequence start [t0Ms]. */
    fun dueAtMs(t0Ms: Long, index: Int): Long {
        val intervalMs = (1000.0 / fps.coerceAtLeast(1)).toLong().coerceAtLeast(1L)
        return t0Ms + index * intervalMs
    }
}
