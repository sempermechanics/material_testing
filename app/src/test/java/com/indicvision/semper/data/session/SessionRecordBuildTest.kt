package com.indicvision.semper.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.fixtures.CleanAppState
import com.indicvision.semper.report.EngineStats
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [SessionRepository.buildSessionRecord]: the grouped inputs land in the same
 * row fields the one-value-per-parameter form fills.
 */
@RunWith(RobolectricTestRunner::class)
class SessionRecordBuildTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = SessionRepository()
    private val settings = SessionRecordSettings(41, 5, 21, 1, 2, 300, 400, use6x6 = true)
    private val stats = List(EngineStats.SLOT_COUNT) { 0f }.toMutableList()
        .also { it[EngineStats.SLOT_CONVERGENCE] = 97.5f }

    private val input = RunInput(
        localSessionId = "s1",
        dir = File("sessions/s1"),
        reference = RunReference("/ref.png", "plate.tif", ImageSize(4000, 3000)),
        settings = settings,
    )
    private val outcome = RunOutcome(
        frameCount = 2,
        defNames = listOf("a.tif", "b.tif", "c.tif"),
        metrics = RunMetrics(pointsConverged = 1180, avgIterations = 2.3f, executionTimeMs = 812, engineStats = stats),
        stopCode = -3,
        plannedFrameCount = 3,
    )

    @Test
    fun `the grouped inputs fill the row`() {
        val record = repository.buildSessionRecord(context, input, outcome, cloudEnabled = true)

        assertEquals("s1", record.id)
        assertEquals(File("sessions/s1").absolutePath, record.sessionDir)
        assertEquals(listOf(4000, 3000), listOf(record.imgW, record.imgH))
        assertEquals(listOf(1, 2, 300, 400), listOf(record.roiX, record.roiY, record.roiW, record.roiH))
        assertEquals(listOf(41, 5, 21), listOf(record.subset, record.step, record.strainWindow))
        assertEquals("/ref.png", record.refPath)
        assertEquals("plate.tif", record.refName)
        assertEquals(2, record.frameCount)
        assertEquals(-3, record.stopCode)
        assertEquals(3, record.plannedFrameCount)
        assertEquals(stats, record.engineStats)
        assertEquals("97.5% converged on frame 1", record.headline)
        assertEquals(SessionRecord.SyncState.PENDING, record.syncState)
        assertEquals(SessionNaming.defaultSessionName("plate.tif", record.createdAt), record.name)
    }

    @Test
    fun `the one-value-per-parameter form builds the same row`() {
        val grouped = repository.buildSessionRecord(context, input, outcome, cloudEnabled = false)
        val flat = repository.buildSessionRecord(
            appContext = context,
            localSessionId = "s1",
            batchDir = File("sessions/s1"),
            refPngPath = "/ref.png",
            refName = "plate.tif",
            realRefWidth = 4000,
            realRefHeight = 3000,
            settings = settings,
            cloudEnabled = false,
            pointsConverged = 1180,
            avgIterations = 2.3f,
            executionTimeMs = 812,
            frameCount = 2,
            defNames = listOf("a.tif", "b.tif", "c.tif"),
            engineStatsArray = stats.toFloatArray(),
            stopCode = -3,
            plannedFrameCount = 3,
        )

        // Only the clock differs between the two calls.
        val sameClock = flat.copy(createdAt = grouped.createdAt, updatedAt = grouped.updatedAt, name = grouped.name)
        assertEquals(grouped, sameClock)
    }

    @Test
    fun `a re-run keeps the user's own name`() {
        val first = repository.buildSessionRecord(context, input, outcome, cloudEnabled = false)
        SessionStore.upsert(context, first.copy(name = "Mine", renamedByUser = true))

        val rerun = repository.buildSessionRecord(context, input, outcome, cloudEnabled = false)

        assertEquals("Mine", rerun.name)
        assertEquals(first.createdAt, rerun.createdAt)
    }
}
