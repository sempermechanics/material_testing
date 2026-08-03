package com.indicvision.semper.cloud

import androidx.work.ListenableWorker
import com.indicvision.semper.data.UploadWorkOutcomes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [DicUploadWorker] quota / fail / retry seams without WorkManager.
 */
class DicUploadWorkerOutcomesTest {

    @Test
    fun `quota exhausted fails permanently`() {
        assertEquals(ListenableWorker.Result.failure(), UploadWorkOutcomes.fromHttpCode(409))
        assertTrue(UploadWorkOutcomes.isQuotaExhausted(409))
        assertFalse(UploadWorkOutcomes.isQuotaExhausted(413))
        assertEquals(ListenableWorker.Result.failure(), UploadWorkOutcomes.fromHttpCode(413))
    }

    @Test
    fun `stale session retries so the next run rebuilds`() {
        assertEquals(ListenableWorker.Result.retry(), UploadWorkOutcomes.fromHttpCode(400))
    }

    @Test
    fun `transient HTTP codes retry`() {
        assertEquals(ListenableWorker.Result.retry(), UploadWorkOutcomes.fromHttpCode(500))
        assertEquals(ListenableWorker.Result.retry(), UploadWorkOutcomes.fromHttpCode(503))
        assertEquals(ListenableWorker.Result.retry(), UploadWorkOutcomes.fromHttpCode(429))
    }

    @Test
    fun `resume classifier — completed is done`() {
        assertEquals(
            UploadWorkOutcomes.ResumeKind.DONE,
            UploadWorkOutcomes.classifyResume("COMPLETED", pendingCount = 0, allPendingMatchArtifacts = true),
        )
    }

    @Test
    fun `resume classifier — mismatch or empty pending rebuilds`() {
        assertEquals(
            UploadWorkOutcomes.ResumeKind.REBUILD,
            UploadWorkOutcomes.classifyResume("UPLOADING", pendingCount = 2, allPendingMatchArtifacts = false),
        )
        assertEquals(
            UploadWorkOutcomes.ResumeKind.REBUILD,
            UploadWorkOutcomes.classifyResume("UPLOADING", pendingCount = 0, allPendingMatchArtifacts = true),
        )
    }

    @Test
    fun `resume classifier — matching pending continues`() {
        assertEquals(
            UploadWorkOutcomes.ResumeKind.CONTINUE,
            UploadWorkOutcomes.classifyResume("UPLOADING", pendingCount = 2, allPendingMatchArtifacts = true),
        )
    }
}
