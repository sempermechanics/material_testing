package com.indicvision.semper.ui.analysis.wizard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.data.session.SessionStore.UpsertResult
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.field.RunStop
import com.indicvision.semper.ui.analysis.run.afterSave
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A solved sweep's session save decides how the sweep ends, as a batch run's
 * does. The sweep used to ignore the save, so a full quota or an unreadable
 * index ended as a completed sweep with nothing on Home.
 */
@RunWith(RobolectricTestRunner::class)
class SweepSaveTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val recorded = mutableListOf<Pair<String, Map<String, String>>>()

    @Before
    fun setUp() {
        SemperAnalytics.sink = SemperAnalytics.Sink { _, name, params -> recorded += name to params }
        DicSettings.setDiagnosticsEnabled(context, true)
    }

    @After
    fun tearDown() {
        SemperAnalytics.sink = SemperAnalytics.Sink { _, _, _ -> }
        DicSettings.setDiagnosticsEnabled(context, false)
    }

    /** Nine combinations solved, two skipped with a positive engine code. */
    private val solved = BatchAnalysisOutcome(
        engineErrorCode = 7,
        firstFrameValidPoints = 400,
        totalFrames = 9,
        executionTimeMs = 3_000,
        batchDirPath = "",
    )

    private fun end(result: UpsertResult): BatchAnalysisOutcome =
        solved.afterSweepSave(afterSave(solved.stop, result)).also { sweepEndEvent(context, it) }

    @Test
    fun `a saved sweep keeps its code and completes`() {
        val outcome = end(UpsertResult.SAVED)

        assertEquals(solved.copy(saved = true), outcome)
        assertEquals(
            listOf(
                SemperAnalytics.ANALYSIS_COMPLETED to
                    mapOf("mode" to "sweep", "frames" to "6_20", "duration" to "1_5s"),
            ),
            recorded,
        )
    }

    @Test
    fun `a full quota at save time ends the sweep at the session limit`() {
        val outcome = end(UpsertResult.QUOTA_FULL)

        assertEquals(RunStop.SessionLimit, outcome.stop)
        assertEquals(false, outcome.saved)
        assertEquals(false, outcome.indexUnavailable)
        assertEquals(
            listOf(
                SemperAnalytics.ANALYSIS_FAILED to
                    mapOf("mode" to "sweep", "reason" to "session_limit", "duration" to "1_5s"),
            ),
            recorded,
        )
    }

    @Test
    fun `an unavailable index says not saved, not the quota`() {
        val outcome = end(UpsertResult.INDEX_UNAVAILABLE)

        assertEquals(solved.copy(saved = false, indexUnavailable = true), outcome)
        assertEquals(
            listOf(
                SemperAnalytics.ANALYSIS_FAILED to
                    mapOf("mode" to "sweep", "reason" to "index_unavailable", "duration" to "1_5s"),
            ),
            recorded,
        )
    }
}
