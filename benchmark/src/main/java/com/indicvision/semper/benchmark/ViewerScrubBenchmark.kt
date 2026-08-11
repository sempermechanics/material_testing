package com.indicvision.semper.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Result-viewer scrub Macrobenchmark. Seeds a synthetic N-frame session via
 * [BenchmarkSeedActivity] (benchmark-variant-only), opens the viewer on frame 0, then
 * flips through frames with the Next button while measuring:
 * - [FrameTimingMetric] — scrub jank (round-2 B1 moved stats/extrema off the UI thread).
 * - [MemoryUsageMetric] — heap under the workload.
 * - [TraceSectionMetric] on `Semper.viewer.decodeDat` — the per-frame `.dat` decode
 *   (round-1 mmap).
 *
 * Runs against the release-like `benchmark` app variant (Macrobenchmark requires a
 * non-debuggable target). Compare builds by running this against `main` and this branch:
 * `./gradlew :benchmark:connectedBenchmarkAndroidTest`.
 */
@OptIn(ExperimentalMetricApi::class)
@LargeTest
@RunWith(AndroidJUnit4::class)
class ViewerScrubBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrub150Frames() = scrub(frameCount = 150)

    @Test
    fun scrub10Frames() = scrub(frameCount = 10)

    private fun scrub(frameCount: Int) = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
            TraceSectionMetric("Semper.viewer.decodeDat", TraceSectionMetric.Mode.Sum),
        ),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            startActivityAndWait(
                Intent().apply {
                    setClassName(PACKAGE, SEEDER)
                    putExtra("frameCount", frameCount)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            // Wait for the viewer's frame controls to appear before measuring.
            device.wait(Until.hasObject(By.res(PACKAGE, "btnNextFrame")), FIND_TIMEOUT_MS)
        },
    ) {
        val next = device.findObject(By.res(PACKAGE, "btnNextFrame"))
            ?: error("btnNextFrame not found — did the viewer open on the seeded session?")
        repeat(SCRUBS) {
            next.click()
            device.waitForIdle(IDLE_TIMEOUT_MS)
        }
    }

    companion object {
        private const val PACKAGE = "com.indicvision.semper"
        private const val SEEDER = "com.indicvision.semper.benchmark.BenchmarkSeedActivity"
        private const val ITERATIONS = 5
        private const val SCRUBS = 12
        private const val FIND_TIMEOUT_MS = 10_000L
        private const val IDLE_TIMEOUT_MS = 3_000L
    }
}
