package com.indicvision.semper.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-screen startup and scroll benchmarks.
 *
 * [StartupBenchmark] only covers app launch, which misses the two screens where
 * jank actually shows up: the settings sheet (the highest-churn file in the app,
 * an 800+ line layout) and the analysis wizard (a ~900 line layout inflated in
 * one pass).
 *
 * **Why these two screens and not Home / the result viewer:** the `benchmark`
 * build type is `initWith(release)`, so `BuildConfig.DEBUG` and `DEV_AUTH_BYPASS`
 * are both false and `DevAuth.active` is off. A launch therefore routes
 * `SplashActivity → AuthActivity`, and the Home session list / result viewer are
 * unreachable without real credentials plus seeded DIC results. The two screens
 * below are launched directly by component instead — Macrobenchmark starts
 * activities through shell, which can reach non-exported components.
 *
 * Run: `./gradlew :benchmark:connectedBenchmarkAndroidTest`
 *
 * No thresholds are asserted yet — these need one calibration run on real
 * hardware before medians can be committed as a regression gate.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ScreenBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** Cold start straight into the settings sheet. */
    @Test
    fun settingsColdStartup() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(),
    ) {
        startActivityAndWait(intentFor(SETTINGS_ACTIVITY))
    }

    /**
     * Frame timing while flinging the settings sheet top to bottom — the screen
     * most likely to regress, since `SettingsActivity.kt` changes most often.
     */
    @Test
    fun settingsScroll() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
        setupBlock = { startActivityAndWait(intentFor(SETTINGS_ACTIVITY)) },
    ) {
        val scroll = device.wait(Until.findObject(By.res(PACKAGE, "settingsScroll")), FIND_TIMEOUT_MS)
            ?: error("settingsScroll not found — did SettingsActivity fail to render standalone?")
        // Keep the gesture clear of the system back/nav edges.
        scroll.setGestureMargin(device.displayWidth / GESTURE_MARGIN_FRACTION)
        repeat(FLINGS) { scroll.fling(Direction.DOWN) }
        repeat(FLINGS) { scroll.fling(Direction.UP) }
        device.waitForIdle()
    }

    /** Cold start into the analysis wizard — the heaviest layout inflation. */
    @Test
    fun analysisWizardColdStartup() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(),
    ) {
        startActivityAndWait(intentFor(ANALYSIS_ACTIVITY))
    }

    private fun intentFor(className: String) = Intent().apply {
        setClassName(PACKAGE, className)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        private const val PACKAGE = "com.indicvision.semper"
        private const val SETTINGS_ACTIVITY = "com.indicvision.semper.ui.settings.SettingsActivity"
        private const val ANALYSIS_ACTIVITY = "com.indicvision.semper.ui.analysis.StaticAnalysisActivity"
        private const val ITERATIONS = 5
        private const val FLINGS = 3
        private const val FIND_TIMEOUT_MS = 5_000L
        private const val GESTURE_MARGIN_FRACTION = 5
    }
}
