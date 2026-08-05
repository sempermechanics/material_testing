package com.indicvision.semper.data

import android.content.Context
import com.indicvision.semper.EngineDebug
import com.indicvision.semper.ui.analysis.FrameImportHelper
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reclaims `cacheDir` leftovers.
 *
 * Nothing pruned these before: a finished analysis left a full copy of every
 * imported image behind, a crashed import left its staging directory, and a
 * killed upload/restore worker left its scratch file — all of which survived
 * until the OS decided to evict the cache, which on a device with space to
 * spare is never. On a long batch of large photos that was hundreds of
 * megabytes sitting idle.
 *
 * Both entry points are safe to call from any thread but do blocking I/O, so
 * call them off the main thread.
 */
object CacheJanitor {

    /** Scratch files workers write straight into `cacheDir`, by name prefix. */
    private val WORKER_SCRATCH_PREFIXES = listOf("restore_", "upload_")

    /** Share/export bundles, which the user may still be picking a target for. */
    private const val SHARE_SUBDIR = "share"

    /**
     * A worker can be running in a freshly started process, so its scratch file
     * is only stale once no plausible run could still own it. A slow upload of a
     * large session over a poor connection is the case this has to clear.
     */
    private const val SCRATCH_MAX_AGE_HOURS = 6L
    private val SCRATCH_MAX_AGE_MS = TimeUnit.HOURS.toMillis(SCRATCH_MAX_AGE_HOURS)

    /** Share bundles outlive the share sheet, but not by a day. */
    private const val SHARE_MAX_AGE_DAYS = 1L
    private val SHARE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(SHARE_MAX_AGE_DAYS)

    /**
     * Full sweep for app start, where no import, analysis or share can be in
     * flight — the view model holding the staged image paths does not survive
     * process death, so a committed import found here is already orphaned.
     */
    fun sweepOnStartup(context: Context): Long = sweep(context.cacheDir, includeStagedImport = true)

    /**
     * Sweep for an explicit "clear cache", which can run while another screen
     * holds a freshly imported batch — so the staged import is left alone.
     */
    fun sweepUserRequested(context: Context): Long = sweep(context.cacheDir, includeStagedImport = false)

    private fun sweep(cacheDir: File, includeStagedImport: Boolean): Long {
        if (!cacheDir.isDirectory) return 0L
        val now = System.currentTimeMillis()
        var freed = 0L

        cacheDir.listFiles()?.forEach { entry ->
            // The share directory is pruned by age from the inside, not removed.
            if (entry.name == SHARE_SUBDIR) {
                freed += sweepShareDir(entry, now)
                return@forEach
            }
            val stale = when {
                // Engine diagnostics. Release builds no longer write these at all,
                // so this is also how a device upgrading from a build that did
                // gets the space back.
                entry.name == EngineDebug.DIR_NAME -> true

                // A crashed import, or a commit swap interrupted midway.
                entry.name.startsWith(FrameImportHelper.STAGING_DIR_PREFIX) -> true
                entry.name == FrameImportHelper.PREVIOUS_DIR_NAME -> true

                // A finished run moves its frames out, leaving this empty; a
                // cancelled one leaves the frames it never reached.
                entry.name == FrameImportHelper.COMMITTED_DIR_NAME -> includeStagedImport

                WORKER_SCRATCH_PREFIXES.any { entry.name.startsWith(it) } ->
                    now - entry.lastModified() > SCRATCH_MAX_AGE_MS

                else -> false
            }
            if (stale) freed += deleteTree(entry)
        }

        if (freed > 0) Timber.d("CacheJanitor reclaimed %d bytes", freed)
        return freed
    }

    private fun sweepShareDir(shareDir: File, now: Long): Long {
        var freed = 0L
        shareDir.listFiles()?.forEach { entry ->
            if (now - entry.lastModified() > SHARE_MAX_AGE_MS) freed += deleteTree(entry)
        }
        return freed
    }

    /** Size of [file] (recursively), or 0 if it could not be removed. */
    private fun deleteTree(file: File): Long {
        val size = sizeOf(file)
        return if (file.deleteRecursively()) size else 0L
    }

    /** Total bytes held by [file], following directories. */
    fun sizeOf(file: File): Long =
        if (file.isDirectory) file.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else file.length()
}
