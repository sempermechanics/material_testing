package com.indicvision.semper.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
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
 * The student lab's Results page: a tensile session with a load per frame opens
 * on its stress–strain curve, and the Whole test / Elastic region toggle redraws
 * it. Measures
 * - [TraceSectionMetric] on `Semper.viewer.stressStrain` — building the curve
 *   from every frame's `.dat` (`ViewerStressStrainHelper.TRACE_BUILD`),
 * - [FrameTimingMetric] — the open and the toggles.
 *
 * Opened the same way as [ViewerScrubBenchmark], through the benchmark-only
 * seeder (`--ez results true`), inside the measured block so the build is in
 * the trace. The Elastic button only reaches the accessibility tree once the
 * curve is drawn and a fit found, so waiting for it waits for the build.
 */
@OptIn(ExperimentalMetricApi::class)
@LargeTest
@RunWith(AndroidJUnit4::class)
class LabResultsBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun results30Frames() = results(frameCount = 30)

    @Test
    fun results150Frames() = results(frameCount = 150)

    private fun results(frameCount: Int) = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric(TRACE_BUILD, TraceSectionMetric.Mode.Sum),
        ),
        iterations = ITERATIONS,
        compilationMode = CompilationMode.Partial(),
        setupBlock = { killProcess() },
    ) {
        device.executeShellCommand(
            "am start -n $PACKAGE/$SEEDER --ez results true --ei frameCount $frameCount",
        )
        check(device.wait(Until.hasObject(By.res(PACKAGE, ELASTIC_BUTTON)), LAUNCH_TIMEOUT_MS)) {
            "Results did not show a fit on the seeded $frameCount-frame tensile session"
        }
        repeat(TOGGLES) {
            device.findObject(By.res(PACKAGE, ELASTIC_BUTTON))?.click()
            device.waitForIdle(IDLE_TIMEOUT_MS)
            device.findObject(By.res(PACKAGE, WHOLE_BUTTON))?.click()
            device.waitForIdle(IDLE_TIMEOUT_MS)
        }
    }

    companion object {
        private const val PACKAGE = "com.indicvision.semper"
        private const val SEEDER = "com.indicvision.semper.benchmark.BenchmarkSeedActivity"
        private const val TRACE_BUILD = "Semper.viewer.stressStrain"
        private const val ELASTIC_BUTTON = "btnRangeElastic"
        private const val WHOLE_BUTTON = "btnRangeWhole"
        private const val ITERATIONS = 5
        private const val TOGGLES = 3
        private const val LAUNCH_TIMEOUT_MS = 120_000L
        private const val IDLE_TIMEOUT_MS = 5_000L
    }
}
