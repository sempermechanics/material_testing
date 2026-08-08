package com.indicvision.semper.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.EngineDebug
import com.indicvision.semper.data.CacheJanitor
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.SessionPaths
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SessionRepository
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.StorageBudget
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.ui.analysis.FrameImportHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A long batch of large photos used to leave two full copies of every image on
 * disk plus an unbounded debug dump, which is what made storage the bottleneck.
 * These pin the three behaviours that fixed it: images are moved rather than
 * copied, cache leftovers are reclaimed, and the auto-free budget only ever
 * touches sessions the cloud already has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalStorageFootprintTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        TokenStore.clear(ctx)
        SessionStore.deleteAll(ctx)
        DicSettings.setAutoFreeBudgetGb(ctx, DicSettings.AUTO_FREE_OFF)
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        SessionStore.deleteAll(ctx)
        TokenStore.clear(ctx)
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    // ── Images move rather than duplicate ────────────────────────────────

    @Test
    fun `persisting a deformed frame moves it out of the import cache`() {
        val staged = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply { mkdirs() }
        val source = File(staged, "0000_specimen.png").apply { writeText("image-bytes") }
        val batchDir = SessionStore.dirFor(ctx, "s1")

        val name = SessionRepository().persistRawDeformed(
            batchDir,
            frameIndex = 0,
            defFilePaths = listOf(source.absolutePath),
            defOriginalNames = listOf("specimen.png"),
        )

        assertEquals("specimen.png", name)
        val persisted = File(File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR), "specimen.png")
        assertEquals("image-bytes", persisted.readText())
        // The point of the change: one copy on disk, not two.
        assertFalse("staged copy should have been moved, not duplicated", source.exists())
    }

    @Test
    fun `re-running over an already persisted frame keeps it`() {
        val batchDir = SessionStore.dirFor(ctx, "s2")
        val rawDir = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }
        val alreadyThere = File(rawDir, "specimen.png").apply { writeText("image-bytes") }

        val name = SessionRepository().persistRawDeformed(
            batchDir,
            frameIndex = 0,
            defFilePaths = listOf(alreadyThere.absolutePath),
            defOriginalNames = listOf("specimen.png"),
        )

        // The old code wiped raw_deformed/ before writing, which on a re-run
        // destroyed the very file it was about to read.
        assertEquals("specimen.png", name)
        assertTrue(alreadyThere.exists())
        assertEquals("image-bytes", alreadyThere.readText())
    }

    @Test
    fun `persisting a frame drops an earlier run's leftovers`() {
        val batchDir = SessionStore.dirFor(ctx, "s3")
        val rawDir = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }
        val stale = File(rawDir, "old.png").apply { writeText("old") }
        val staged = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply { mkdirs() }
        val source = File(staged, "0000_new.png").apply { writeText("new") }

        SessionRepository().persistRawDeformed(
            batchDir,
            frameIndex = 0,
            defFilePaths = listOf(source.absolutePath),
            defOriginalNames = listOf("new.png"),
        )

        assertFalse(stale.exists())
        assertTrue(File(rawDir, "new.png").exists())
    }

    // ── Cache leftovers ─────────────────────────────────────────────────

    @Test
    fun `startup sweep reclaims the debug dump and import leftovers`() {
        val debug = File(ctx.cacheDir, EngineDebug.DIR_NAME).apply { mkdirs() }
        File(debug, "correlation_heatmap.png").writeText("x".repeat(64))
        File(ctx.cacheDir, "${FrameImportHelper.STAGING_DIR_PREFIX}123").apply { mkdirs() }
        File(ctx.cacheDir, FrameImportHelper.PREVIOUS_DIR_NAME).apply { mkdirs() }
        val committed = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply { mkdirs() }
        File(committed, "frame.png").writeText("bytes")
        val keep = File(ctx.cacheDir, "roi_mask_cache.bin").apply { writeText("mask") }

        val freed = CacheJanitor.sweepOnStartup(ctx)

        assertFalse(debug.exists())
        assertFalse(File(ctx.cacheDir, "${FrameImportHelper.STAGING_DIR_PREFIX}123").exists())
        assertFalse(File(ctx.cacheDir, FrameImportHelper.PREVIOUS_DIR_NAME).exists())
        assertFalse(committed.exists())
        assertTrue("unrelated cache files must survive", keep.exists())
        assertTrue(freed > 0)
    }

    @Test
    fun `user-requested sweep leaves a freshly imported batch alone`() {
        val committed = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply { mkdirs() }
        File(committed, "frame.png").writeText("bytes")
        val debug = File(ctx.cacheDir, EngineDebug.DIR_NAME).apply { mkdirs() }

        CacheJanitor.sweepUserRequested(ctx)

        // Another screen may be holding these paths; only startup can assume not.
        assertTrue(committed.exists())
        assertFalse(debug.exists())
    }

    @Test
    fun `recent worker scratch survives a sweep`() {
        val scratch = File(ctx.cacheDir, "restore_abc_bundle.zip").apply { writeText("in-flight") }

        CacheJanitor.sweepOnStartup(ctx)

        assertTrue("a running worker's scratch file must not be pulled out from under it", scratch.exists())
    }

    @Test
    fun `user clear reclaims stale transfer scratch and regenerable files`() {
        val scratch = File(ctx.cacheDir, "restore_abc_bundle.zip").apply { writeText("x".repeat(2048)) }
        // Older than the 2-minute user grace window.
        scratch.setLastModified(System.currentTimeMillis() - java.util.concurrent.TimeUnit.MINUTES.toMillis(5))
        val mask = File(ctx.cacheDir, "roi_mask_cache.bin").apply { writeText("mask") }
        val committed = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply { mkdirs() }
        File(committed, "frame.png").writeText("keep-me")

        val clearable = CacheJanitor.clearableUserBytes(ctx)
        assertTrue("meter should count scratch + mask", clearable >= 2048)
        val freed = CacheJanitor.sweepUserRequested(ctx)

        assertFalse(scratch.exists())
        assertFalse(mask.exists())
        assertTrue(committed.exists())
        assertTrue(freed >= 2048)
        assertEquals(0L, CacheJanitor.clearableUserBytes(ctx))
    }

    @Test
    fun `user clear leaves mid-write scratch alone`() {
        val scratch = File(ctx.cacheDir, "upload_xyz_frame.pdf").apply { writeText("hot") }
        scratch.setLastModified(System.currentTimeMillis())

        assertEquals(0L, CacheJanitor.clearableUserBytes(ctx))
        CacheJanitor.sweepUserRequested(ctx)

        assertTrue(scratch.exists())
    }

    // ── Auto-free budget ────────────────────────────────────────────────

    @Test
    fun `freeing space drops backed-up sessions and spares the rest`() {
        val synced = seedSession("synced", SessionRecord.SyncState.SYNCED)
        val localOnly = seedSession("local", SessionRecord.SyncState.LOCAL_ONLY)
        val pending = seedSession("pending", SessionRecord.SyncState.PENDING)

        val outcome = StorageBudget.freeAllBackedUp(ctx)

        assertEquals(1, outcome.sessionsDropped)
        assertTrue(outcome.freedBytes > 0)
        assertFalse(File(synced, "frame_0000.dat").exists())
        // This device holds the only copy of these two — dropping them would be
        // data loss, not eviction.
        assertTrue(File(localOnly, "frame_0000.dat").exists())
        assertTrue(File(pending, "frame_0000.dat").exists())
        // The reference survives so the Home row still renders as "only in cloud".
        assertTrue(File(synced, "reference.png").exists())
    }

    @Test
    fun `budget of off never drops anything`() {
        seedSession("synced", SessionRecord.SyncState.SYNCED)
        DicSettings.setAutoFreeBudgetGb(ctx, DicSettings.AUTO_FREE_OFF)

        val outcome = StorageBudget.enforce(ctx)

        assertEquals(0, outcome.sessionsDropped)
        assertTrue(SessionStore.get(ctx, "synced")!!.hasLocalData())
    }

    @Test
    fun `reclaimable bytes counts only backed-up sessions`() {
        seedSession("synced", SessionRecord.SyncState.SYNCED)
        seedSession("local", SessionRecord.SyncState.LOCAL_ONLY)

        val reclaimable = StorageBudget.reclaimableBytes(ctx)

        assertEquals(SessionStore.sizeOf(ctx, "synced"), reclaimable)
    }

    /** A session directory with the artifacts a real run leaves behind. */
    private fun seedSession(id: String, state: SessionRecord.SyncState): File {
        val dir = SessionStore.dirFor(ctx, id)
        File(dir, "frame_0000.dat").writeText("d".repeat(512))
        File(dir, "reference.png").writeText("ref")
        File(dir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }
            .let { File(it, "specimen.png").writeText("i".repeat(2048)) }
        SessionStore.upsert(
            ctx,
            SessionRecord(
                id = id,
                name = id,
                createdAt = 1L,
                updatedAt = 1L,
                frameCount = 1,
                subset = 41,
                step = 5,
                strainWindow = 15,
                imgW = 100,
                imgH = 100,
                roiX = 0,
                roiY = 0,
                roiW = 100,
                roiH = 100,
                refPath = File(dir, "reference.png").absolutePath,
                refName = "reference.png",
                sessionDir = dir.absolutePath,
                syncState = state,
            ),
        )
        return dir
    }
}
