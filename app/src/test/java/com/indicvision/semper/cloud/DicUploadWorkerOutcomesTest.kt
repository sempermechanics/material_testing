package com.indicvision.semper.cloud

import androidx.work.ListenableWorker
import com.indicvision.semper.data.UploadWorkOutcomes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `resume classifier — provisioning waits instead of rebuilding`() {
        // The backend opens Drive upload targets in a Cloud Task, so a fresh
        // session legitimately reports zero pending uploads for a moment.
        // Rebuilding here would spin: every poll would mint another session.
        assertEquals(
            UploadWorkOutcomes.ResumeKind.WAIT,
            UploadWorkOutcomes.classifyResume(
                "PROVISIONING",
                pendingCount = 0,
                allPendingMatchArtifacts = true,
            ),
        )
    }

    @Test
    fun `resume classifier — provisioning wins over an apparent mismatch`() {
        // Nothing is provisioned yet, so "the pending set does not match our
        // artifacts" is not evidence of anything. Waiting must take precedence.
        assertEquals(
            UploadWorkOutcomes.ResumeKind.WAIT,
            UploadWorkOutcomes.classifyResume(
                "PROVISIONING",
                pendingCount = 0,
                allPendingMatchArtifacts = false,
            ),
        )
    }

    @Test
    fun `resume classifier — failed provisioning is terminal kind`() {
        // Must not collapse into REBUILD: that spun create→fail→delete→retry.
        assertEquals(
            UploadWorkOutcomes.ResumeKind.PROVISION_FAILED,
            UploadWorkOutcomes.classifyResume(
                "PROVISION_FAILED",
                pendingCount = 0,
                allPendingMatchArtifacts = true,
            ),
        )
    }

    @Test
    fun `resume classifier — completed still wins over provisioning`() {
        assertEquals(
            UploadWorkOutcomes.ResumeKind.DONE,
            UploadWorkOutcomes.classifyResume(
                "COMPLETED",
                pendingCount = 0,
                allPendingMatchArtifacts = true,
            ),
        )
    }

    @Test
    fun `staging is reusable only with complete bundle artifacts and non-empty zip`() {
        val dir = createTempDir(prefix = "upload-staging-")
        try {
            assertFalse(UploadWorkOutcomes.stagingReusable(dir))
            File(dir, ".bundles_done").createNewFile()
            assertFalse(UploadWorkOutcomes.stagingReusable(dir))
            File(dir, "Session.zip").writeText("zip-bytes")
            // Marker + zip alone used to count as done — that froze incomplete
            // uploads that skipped reports/csv/processed.
            assertFalse(UploadWorkOutcomes.stagingReusable(dir))
            assertFalse(UploadWorkOutcomes.bundleArtifactsReady(dir))

            File(dir, "analysis_data.csv").writeText("image,x,y\n")
            File(dir, "reports").mkdirs()
            File(dir, "reports/Master_Report_Frame_1.pdf").writeText("%PDF")
            File(dir, "processed/Frame_1").mkdirs()
            File(dir, "processed/Frame_1/exx.png").writeText("png")
            assertTrue(UploadWorkOutcomes.bundleArtifactsReady(dir))
            assertTrue(UploadWorkOutcomes.stagingReusable(dir))

            File(dir, "Session.zip").writeText("")
            assertFalse(UploadWorkOutcomes.stagingReusable(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `bundleArtifactsReady rejects missing csv reports or processed`() {
        val dir = createTempDir(prefix = "upload-staging-")
        try {
            File(dir, "analysis_data.csv").writeText("image\n")
            File(dir, "reports").mkdirs()
            File(dir, "reports/Master_Report_Frame_1.pdf").writeText("%PDF")
            assertFalse(UploadWorkOutcomes.bundleArtifactsReady(dir))

            File(dir, "processed/Frame_1").mkdirs()
            File(dir, "processed/Frame_1/exx.png").writeText("png")
            assertTrue(UploadWorkOutcomes.bundleArtifactsReady(dir))

            File(dir, "reports/Master_Report_Frame_1.pdf").delete()
            assertFalse(UploadWorkOutcomes.bundleArtifactsReady(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
