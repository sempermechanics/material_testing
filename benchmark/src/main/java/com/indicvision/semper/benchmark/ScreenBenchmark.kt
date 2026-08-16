package com.indicvision.semper.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
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
 * `SplashActivity → AuthActivity`, and the Home session list is unreachable
 * without real credentials. The two screens below are launched directly by
 * component instead. (The result viewer *is* covered — by [ViewerScrubBenchmark],
 * which seeds a synthetic session.)
 *
 * Both screens are `android:exported="false"` in every shipped variant, and since
 * API 34 the shell (uid 2000) cannot start a non-exported component — `am start`
 * fails with "SecurityException: Permission Denial ... not exported". They are
 * therefore exported for the `benchmark` variant only, via the overlay at
 * `app/src/benchmark/AndroidManifest.xml`.
 *
 * **Known environment limitation:** on an API 37 emulator these still fail with
 * "Unable to confirm activity launch completion []" — `startActivityAndWait`
 * confirms a launch by parsing `dumpsys gfxinfo <pkg> framestats`, which comes back
 * empty there for *every* activity (exported or not, trampoline or not). Anything
 * built on `startActivityAndWait`/`StartupTimingMetric` is therefore unrunnable on
 * that emulator; run these on a physical device or an older API image.
 * [ViewerScrubBenchmark] deliberately avoids that API and does run.
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
     * Frame timing while flinging settings top to bottom. Sections start
     * collapsed, so a fling on the empty headers produces no frames and
     * [FrameTimingMetric] throws "Observed no expect/actual slices". Expand
     * first in [setupBlock]; measure only the scroll.
     */
    @Test
    fun settingsScroll() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            startActivityAndWait(intentFor(SETTINGS_ACTIVITY))
            expandAllSettingsSections()
        },
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

    private fun MacrobenchmarkScope.expandAllSettingsSections() {
        val scroll = device.wait(Until.findObject(By.res(PACKAGE, "settingsScroll")), FIND_TIMEOUT_MS)
            ?: error("settingsScroll not found — did SettingsActivity fail to render standalone?")
        scroll.setGestureMargin(device.displayWidth / GESTURE_MARGIN_FRACTION)
        SECTION_HEADERS.forEach { id ->
            val selector = By.res(PACKAGE, id)
            if (!device.hasObject(selector)) {
                var attempts = 0
                while (!device.hasObject(selector) && attempts++ < SCROLL_ATTEMPTS) {
                    scroll.fling(Direction.DOWN)
                }
            }
            val header = device.findObject(selector)
                ?: error("$id not found — settings section header missing")
            header.click()
            device.waitForIdle()
        }
        repeat(SCROLL_ATTEMPTS) { scroll.fling(Direction.UP) }
        device.waitForIdle()
    }

    companion object {
        private const val PACKAGE = "com.indicvision.semper"
        private const val SETTINGS_ACTIVITY = "com.indicvision.semper.ui.settings.SettingsActivity"
        private const val ANALYSIS_ACTIVITY = "com.indicvision.semper.ui.analysis.StaticAnalysisActivity"
        private const val ITERATIONS = 5
        private const val FLINGS = 3
        private const val FIND_TIMEOUT_MS = 5_000L
        private const val GESTURE_MARGIN_FRACTION = 5
        private const val SCROLL_ATTEMPTS = 8
        private val SECTION_HEADERS = listOf(
            "headerAccount",
            "headerCloud",
            "headerAnalysesData",
            "headerStorage",
            "headerYourData",
            "headerAnalysisPrefs",
            "headerHelpSupport",
        )
    }
}
