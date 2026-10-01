package com.indicvision.semper.settings

import androidx.work.WorkInfo
import com.indicvision.semper.data.CloudRestore
import com.indicvision.semper.ui.settings.BusyTransfers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Which settings rows are busy with a restore or a Save-to-Files download.
 *
 * Settings used to answer this with `getWorkInfosForUniqueWork(…).get()` on
 * the main thread — on every row tap and twice per busy row on each WorkInfo
 * change. It now reads the WorkInfo lists its observers already receive; these
 * pin that the answer is the same one the database query gave.
 */
class BusyTransfersTest {

    private fun restore(cloudId: String, state: WorkInfo.State) =
        WorkInfo(UUID.randomUUID(), state, setOf("restore", "restore-$cloudId"))

    private fun download(cloudId: String, state: WorkInfo.State) = WorkInfo(
        UUID.randomUUID(),
        state,
        setOf(CloudRestore.TAG_BUNDLE_DOWNLOAD, "${CloudRestore.TAG_BUNDLE_DOWNLOAD}-$cloudId"),
    )

    @Test
    fun `queued, blocked and running work is busy, finished work is not`() {
        val ids = BusyTransfers.unfinishedIds(
            listOf(
                restore("enq", WorkInfo.State.ENQUEUED),
                restore("run", WorkInfo.State.RUNNING),
                restore("blk", WorkInfo.State.BLOCKED),
                restore("ok", WorkInfo.State.SUCCEEDED),
                restore("bad", WorkInfo.State.FAILED),
                restore("off", WorkInfo.State.CANCELLED),
            ),
            "restore",
        )
        assertEquals(setOf("enq", "run", "blk"), ids)
    }

    @Test
    fun `the cloud id comes from the per-session tag, not the shared one`() {
        val untagged = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, setOf("restore"))
        assertEquals(emptySet<String>(), BusyTransfers.unfinishedIds(listOf(untagged), "restore"))
        assertEquals(
            setOf("c1"),
            BusyTransfers.unfinishedIds(
                listOf(download("c1", WorkInfo.State.RUNNING)),
                CloudRestore.TAG_BUNDLE_DOWNLOAD,
            ),
        )
    }

    @Test
    fun `a restore and a download are told apart, and either makes the row busy`() {
        val busy = BusyTransfers()
        busy.onRestoreWork(listOf(restore("r", WorkInfo.State.RUNNING)))
        busy.onDownloadWork(listOf(download("d", WorkInfo.State.ENQUEUED)))

        assertTrue(busy.isRestoring("r"))
        assertFalse(busy.isRestoring("d"))
        assertTrue(busy.isDownloading("d"))
        assertTrue(busy.isBusy("r"))
        assertTrue(busy.isBusy("d"))
        assertFalse(busy.isBusy("idle"))
        assertEquals(setOf("r", "d"), busy.keys())
    }

    @Test
    fun `a tap marks the row busy before WorkManager lists the job`() {
        val busy = BusyTransfers()
        busy.mark("c1")
        assertTrue(busy.isBusy("c1"))

        // Another job's outcome arrives first: the mark only goes once its own
        // work is reported, and then the list keeps the row busy by itself.
        busy.onRestoreWork(listOf(restore("c1", WorkInfo.State.ENQUEUED)))
        busy.settle()
        assertTrue(busy.isBusy("c1"))

        busy.onRestoreWork(listOf(restore("c1", WorkInfo.State.SUCCEEDED)))
        busy.settle()
        assertFalse("finished work frees the row", busy.isBusy("c1"))
        assertEquals(emptySet<String>(), busy.keys())
    }

    @Test
    fun `a finished download frees its row while a running restore keeps its own`() {
        val busy = BusyTransfers()
        busy.mark("d")
        busy.mark("r")
        busy.onRestoreWork(listOf(restore("r", WorkInfo.State.RUNNING)))
        busy.onDownloadWork(listOf(download("d", WorkInfo.State.FAILED)))
        busy.settle()

        assertEquals(setOf("r"), busy.keys())
    }
}
