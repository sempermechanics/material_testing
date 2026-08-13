@file:Suppress("MagicNumber")

package com.indicvision.semper.perf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.os.Trace
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.indicvision.semper.DicResult
import com.indicvision.semper.ProgressCallback
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.data.CloudRestore
import com.indicvision.semper.data.CloudSync
import com.indicvision.semper.data.SessionPaths
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.SessionZip
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.VisualizationEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.random.Random

/**
 * Headless, component-nucleated workload driver for on-device characterization.
 *
 * Runs against the **installed, logged-in app** — same process space, same
 * [android.content.Context], same [SessionStore]/[TokenProvider] a real user session
 * would use — so every op it drives is the real code path, not a mock. It never touches
 * the wizard UI or the gallery picker: sessions are created directly through the same
 * native-engine + persistence calls [com.indicvision.semper.ui.analysis.AnalysisViewModel]
 * makes, using seeded synthetic speckle (the same generator as
 * [com.indicvision.semper.pipeline.EnginePipelineSmokeTest]) so it is reproducible run to run.
 *
 * Each operation is **nucleated into components** and each component's cost is reported
 * on its own marker line — `SWD| op=<op> comp=<component> t_ms=<n> [bytes=<n>] [extra=…]`
 * — so a host script can key on component names without guessing at boundaries. Where the
 * engine has no finer seam than one native call (e.g. ICGN is not separable from the
 * RGDIC propagation that calls it), the component is reported at the finest granularity
 * the engine's own telemetry exposes ([EngineStats]) rather than invented.
 *
 * Select the operation and scale with instrumentation arguments:
 * ```
 * adb shell am instrument -w \
 *   -e class com.indicvision.semper.perf.SyntheticWorkloadDriver#run \
 *   -e op analysis -e scale large \
 *   com.indicvision.semper.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * `op` ∈ {screen, analysis, backup, restore}; `scale` ∈ {light, large}; `cloudId` (restore
 * only) overrides auto-discovery of the most recently uploaded session.
 */
@RunWith(AndroidJUnit4::class)
class SyntheticWorkloadDriver {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args: Map<String, String>
        get() = InstrumentationRegistry.getArguments().let { bundle ->
            bundle.keySet().associateWith { bundle.getString(it).orEmpty() }
        }

    private fun arg(name: String, default: String): String = args[name]?.takeIf { it.isNotBlank() } ?: default

    @Test
    fun run() {
        val op = arg("op", "analysis")
        val scale = Scale.of(arg("scale", "light"))
        log("driver", "start", extra = "op=$op scale=${scale.name}")
        when (op) {
            "analysis" -> runAnalysis(scale)
            "screen" -> runScreen(scale)
            "backup" -> runBackup(scale)
            "restore" -> runRestore(scale)
            else -> error("Unknown op '$op' — expected analysis|screen|backup|restore")
        }
        log("driver", "end", extra = "op=$op scale=${scale.name}")
    }

    // ── Scale definitions (light / large workload knobs) ─────────────────────

    private enum class Scale(
        val frames: Int,
        val step: Int,
        val subset: Int,
        val strainWin: Int,
        val use6x6: Boolean,
        val imgW: Int,
        val imgH: Int,
    ) {
        LIGHT(frames = 1, step = 20, subset = 21, strainWin = 15, use6x6 = false, imgW = 320, imgH = 320),
        LARGE(frames = 150, step = 2, subset = 81, strainWin = 15, use6x6 = true, imgW = 640, imgH = 640),
        ;

        companion object {
            fun of(name: String): Scale = when (name.lowercase(Locale.US)) {
                "large" -> LARGE
                else -> LIGHT
            }
        }
    }

    // ── Component: speckle + warp generation (not timed — test fixture only) ─

    /** Deterministic speckle reference — same construction as EnginePipelineSmokeTest. */
    private fun makeReference(w: Int, h: Int, seed: Long = 12345L): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(128, 128, 128))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rng = Random(seed)
        val speckleCount = (w * h) / 68 // same density as the 320x320/1500-speckle original
        repeat(speckleCount) {
            val x = rng.nextFloat() * w
            val y = rng.nextFloat() * h
            val r = 1.5f + rng.nextFloat() * 2.5f
            val g = rng.nextInt(0, 256)
            paint.color = Color.rgb(g, g, g)
            canvas.drawCircle(x, y, r, paint)
        }
        return bmp
    }

    /** Frame i is translated by (i * 0.4px, i * 0.2px) — small, monotonic, cheap to warp. */
    private fun warpForFrame(src: Bitmap, w: Int, h: Int, frameIndex: Int): Bitmap {
        val m = Matrix().apply { setTranslate(0.4f * (frameIndex + 1), 0.2f * (frameIndex + 1)) }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(128, 128, 128))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, m, paint)
        return bmp
    }

    private fun Bitmap.toPngBytes(): ByteArray = ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    // ── Operation: analysis (nucleated into its components) ──────────────────

    /** Everything a headless run needs to answer the "screen"/"backup" ops too. */
    private class BuiltSession(val localId: String, val sessionDir: File, val record: SessionRecord)

    private fun runAnalysis(scale: Scale): BuiltSession {
        val localId = "swd-${scale.name.lowercase(Locale.US)}-${SystemClock.elapsedRealtime()}"
        val sessionDir = SessionStore.dirFor(context, localId)
        val rawDeformedDir = SessionStore.rawDeformedDir(sessionDir).apply { mkdirs() }

        // Component: reference + deformed image "decode" — mirrors the wizard's
        // bytes-in-hand step. Generation itself is a fixture, not a measured component;
        // what we time is the same PNG round trip the real pipeline pays for.
        val refBmp = makeReference(scale.imgW, scale.imgH)
        val refBytes = timed("image_encode_decode") {
            val png = refBmp.toPngBytes()
            SemperNativeLib.getImageDimensions(png) // forces native decode, as the wizard does
            png
        }
        File(sessionDir, "reference.png").writeBytes(refBytes)

        // Component: initializeReference (native)
        timed("native_init_reference") {
            SemperNativeLib.initializeReference(refBytes, null, scale.imgW, scale.imgH)
        }

        val step = scale.step
        val subset = scale.subset
        val gridW = scale.imgW / step
        val gridH = scale.imgH / step
        val maxPoints = gridW * gridH
        val buffer = ByteBuffer.allocateDirect(maxPoints * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        val callback = object : ProgressCallback {
            override fun onProgressUpdate(percentage: Int) {}
        }

        var firstStats: EngineStats? = null
        var totalPointsSolved = 0
        var solvedFrames = 0

        Trace.beginSection("Semper.perf.analysis.${scale.name}")
        try {
            for (frameIndex in 0 until scale.frames) {
                val defBmp = warpForFrame(refBmp, scale.imgW, scale.imgH, frameIndex)
                val defName = String.format(Locale.US, "def_%04d.png", frameIndex)
                val defPngBytes = defBmp.toPngBytes()
                File(rawDeformedDir, defName).writeBytes(defPngBytes)
                defBmp.recycle()

                val metrics = FloatArray(EngineStats.SLOT_COUNT) { if (it == EngineStats.SLOT_MESH_SEEDING) -1f else 0f }
                val validPoints = timedFrame(frameIndex) {
                    SemperNativeLib.computeFullFieldDirect(
                        refBytes, defPngBytes, ByteArray(0),
                        0, 0, scale.imgW, scale.imgH,
                        step, subset, scale.strainWin, scale.use6x6,
                        buffer, callback, metrics,
                    )
                }
                assertTrue("engine returned error $validPoints on frame $frameIndex", validPoints >= 0)
                if (frameIndex == 0) {
                    firstStats = EngineStats.fromArray(metrics)
                    logEngineStats(firstStats)
                }
                if (validPoints > 0) {
                    // Component: .dat write (I/O)
                    timed("dat_write", once = frameIndex == 0) {
                        val out = File(sessionDir, String.format(Locale.US, "frame_%04d.dat", frameIndex))
                        val bytes = ByteArray(validPoints * DicResult.BYTES_PER_POINT)
                        buffer.position(0)
                        buffer.get(bytes, 0, bytes.size)
                        out.writeBytes(bytes)
                    }
                    totalPointsSolved += validPoints
                    solvedFrames++
                }
            }
        } finally {
            Trace.endSection()
        }

        assertTrue("no frames solved at scale ${scale.name}", solvedFrames > 0)

        val now = System.currentTimeMillis()
        val record = SessionRecord(
            id = localId,
            name = "SWD ${scale.name} $localId",
            createdAt = now,
            updatedAt = now,
            frameCount = solvedFrames,
            subset = subset,
            step = step,
            strainWindow = scale.strainWin,
            use6x6 = scale.use6x6,
            imgW = scale.imgW,
            imgH = scale.imgH,
            roiX = 0,
            roiY = 0,
            roiW = scale.imgW,
            roiH = scale.imgH,
            refPath = File(sessionDir, "reference.png").absolutePath,
            refName = "reference.png",
            sessionDir = sessionDir.absolutePath,
            defNames = (0 until solvedFrames).map { String.format(Locale.US, "def_%04d.png", it) },
            engineStats = firstStats?.let {
                listOf(
                    it.totalPointsAttempted.toFloat(), it.totalPointsSolved.toFloat(), it.totalPointsRejected.toFloat(),
                    it.pathAPoints.toFloat(), it.pathBPoints.toFloat(), it.simplexCalls.toFloat(),
                    it.simplexSaved.toFloat(), it.finalDeadPoints.toFloat(), it.avgIcgnIterations, it.wallTimeMs,
                    it.akazeRansacMs, it.hessianPrepassMs, it.delaunayMs, it.strainMs, it.avgThroughputPtsPerMs,
                    it.convergencePercent, it.meshSeedingQuality.toFloat(),
                )
            }.orEmpty(),
            strainMethod = "VSG",
            pointsConverged = totalPointsSolved / solvedFrames.coerceAtLeast(1),
            avgIterations = firstStats?.avgIcgnIterations ?: 0f,
            executionTimeMs = 0, // component times are the point; not re-summed here
        )

        // Component: record persist
        val persisted = timed("record_persist") { SessionStore.upsert(context, record, allowOverLimit = true) }
        assertTrue("SessionStore.upsert failed for $localId", persisted)

        log("space", "session_footprint", extra = duSummary(sessionDir))
        return BuiltSession(localId, sessionDir, record)
    }

    // ── Operation: screen refresh (nucleated into its components) ────────────

    private fun runScreen(scale: Scale) {
        val built = runAnalysis(scale)
        val data = requireNotNull(DicResult.decodeDatFile(File(built.sessionDir, "frame_0000.dat"))) {
            "seeded frame_0000.dat failed to decode"
        }

        // Component: decode (already-measured wall time via the actual API, separate call
        // for the isolated per-op timing so it's directly comparable to the analysis dat_write).
        timed("viewer_decode_dat") {
            DicResult.decodeDatFile(File(built.sessionDir, "frame_0000.dat"))
        }

        // Component: stats + percentile extrema
        timed("viewer_field_stats") {
            DicResult.fieldStats(data, DicResult.IDX_EXX)
        }

        // Component: heatmap generate (indices + jet raster)
        timed("viewer_generate_heatmap") {
            val (bmp, _, _) = VisualizationEngine.generateHeatmap(
                data,
                built.record.imgW,
                built.record.imgH,
                DicResult.IDX_EXX,
                built.record.step,
                maxLongEdge = VisualizationEngine.DISPLAY_MAX_EDGE,
            )
            bmp.recycle()
        }

        // Component: whole-batch summary pre-pass (only meaningful at "large" — many frames)
        if (built.record.frameCount > 1) {
            timed("viewer_summary_prepass") {
                val fields = intArrayOf(DicResult.IDX_U, DicResult.IDX_V, DicResult.IDX_EXX, DicResult.IDX_EYY, DicResult.IDX_EXY)
                for (i in 0 until built.record.frameCount) {
                    val frameData = DicResult.decodeDatFile(File(built.sessionDir, String.format(Locale.US, "frame_%04d.dat", i)))
                    if (frameData != null) VisualizationEngine.valueRanges(frameData, fields)
                }
            }
        }
        log("space", "session_footprint", extra = duSummary(built.sessionDir))
    }

    // ── Operation: backup (nucleated into its components) ─────────────────────

    private fun runBackup(scale: Scale) {
        val built = runAnalysis(scale)
        val sessionDir = built.sessionDir
        val rawDeformedDir = SessionStore.rawDeformedDir(sessionDir)

        val refFile = File(sessionDir, "reference.png")
        val datMembers = (0 until built.record.frameCount).map {
            val name = String.format(Locale.US, "frame_%04d.dat", it)
            SessionZip.Member("dat", name, File(sessionDir, name))
        }
        val deformedMembers = built.record.defNames.map {
            SessionZip.Member("raw", it, File(rawDeformedDir, it))
        }

        // Restore-essential: every original image (reference + deformed) + dat — see
        // SessionZip.isRestoreEssential. The derived deliverables (csv/reports/processed)
        // are what goes to Extras.zip; this headless driver doesn't generate them (no
        // report/heatmap rendering), so it has nothing to build a local Extras.zip from.
        // The real Extras.zip — and its object-transfer cost — still happens inside the
        // production DicUploadWorker path enqueued below; its per-object byte/timing
        // detail is read back from logcat by the host script (Timber.d "Uploading %s
        // (%d bytes)…", Timber.i "Uploading %s: %d files" — debug-build only) rather than
        // duplicated here, so the measured transfer is the exact one a real backup performs.
        val bundleMembers = listOf(SessionZip.Member("raw", SessionZip.REFERENCE_NAME, refFile)) +
            deformedMembers + datMembers

        // Component: build Session.zip (restore-essential payload)
        val bundleZip = File(sessionDir, "SWD_Session.zip")
        timed("zip_build_session") { SessionZip.build(bundleMembers, bundleZip) }
        log("backup", "zip_session_bytes", extra = "bytes=${bundleZip.length()}")

        // Component: create-session / per-object transfer / complete — these happen inside
        // DicUploadWorker, which is the real production code path. We enqueue it for real
        // and poll WorkManager for the terminal state.
        val enqueueStart = SystemClock.elapsedRealtime()
        CloudSync.enqueueUpload(context, built.localId)
        val info = awaitUniqueWork(context, "upload-${built.localId}", timeoutMs = UPLOAD_TIMEOUT_MS)
        val elapsed = SystemClock.elapsedRealtime() - enqueueStart
        log("backup", "upload_worker_total", extra = "t_ms=$elapsed state=${info?.state}")
        assertTrue(
            "upload for ${built.localId} did not succeed (state=${info?.state}) — is quota known / is the extras role deployed?",
            info?.state == WorkInfo.State.SUCCEEDED,
        )
        log("space", "session_footprint_post_backup", extra = duSummary(sessionDir))
    }

    // ── Operation: restore (nucleated into its components) ─────────────────────

    private fun runRestore(scale: Scale) {
        // A restore needs a prior backup to restore FROM. `cloudId` may be passed in
        // (to restore an existing, possibly larger/legacy backup); otherwise this drives
        // a fresh backup first so restore is measurable standalone.
        val cloudId = arg("cloudId", "").ifBlank {
            runBackup(scale)
            findMostRecentCloudId()
        }
        val targetLocalId = "swd-restore-${SystemClock.elapsedRealtime()}"

        // Component: list manifest — timed separately even though CloudRestore.restore
        // will call it again internally; this isolates the network round-trip cost.
        timed("restore_list_manifest") {
            runBlocking {
                val token = requireNotNull(TokenProvider.usableIdToken()) { "not signed in" }
                IndicApi.get(context).listSessionFiles(token, cloudId)
            }
        }

        // Component: metadata / bundle-download / unpack / index-upsert all happen inside
        // CloudRestore.restore (the real production path — schema-branch, ranged-prefix
        // fallback, CRC verify). We enqueue for real and poll to completion; the host
        // script reads the per-component byte accounting back from the
        // "downloaded %d of %d backup bytes (%s)" / "Legacy bundle %s: fetching %d of %d"
        // log lines (debug-build only), which name the exact mode (whole-bundle /
        // legacy-ranged-prefix / legacy-per-file) actually used.
        val enqueueStart = SystemClock.elapsedRealtime()
        CloudRestore.enqueueRestore(context, cloudId, targetLocalId)
        val info = awaitUniqueWork(context, CloudRestore.workName(cloudId), timeoutMs = RESTORE_TIMEOUT_MS)
        val elapsed = SystemClock.elapsedRealtime() - enqueueStart
        log("restore", "restore_worker_total", extra = "t_ms=$elapsed state=${info?.state}")
        assertTrue("restore of $cloudId did not succeed (state=${info?.state})", info?.state == WorkInfo.State.SUCCEEDED)

        val restoredDir = SessionStore.dirFor(context, targetLocalId)
        log("space", "restored_session_footprint", extra = duSummary(restoredDir))
    }

    private fun findMostRecentCloudId(): String = runBlocking {
        val token = requireNotNull(TokenProvider.usableIdToken()) { "not signed in" }
        val sessions = IndicApi.get(context).listSessions(token).sessions
        val match = sessions
            .filter { it.localSessionId.startsWith("swd-") }
            .maxByOrNull { it.localSessionId }
        requireNotNull(match) { "no SWD-created cloud session found to restore" }.sessionId
    }

    // ── WorkManager polling (no work-testing dependency; polls the real WorkManager) ──

    private fun awaitUniqueWork(context: Context, uniqueName: String, timeoutMs: Long): WorkInfo? {
        val wm = WorkManager.getInstance(context)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val infos = wm.getWorkInfosForUniqueWork(uniqueName).get()
            val terminal = infos.firstOrNull { it.state.isFinished }
            if (terminal != null) return terminal
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    // ── Timing + logging helpers ──────────────────────────────────────────────

    private inline fun <T> timed(component: String, once: Boolean = true, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        val result = block()
        if (once) {
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            log(component, "done", tMs = ms)
        }
        return result
    }

    private inline fun timedFrame(frameIndex: Int, block: () -> Int): Int {
        val start = SystemClock.elapsedRealtimeNanos()
        val result = block()
        val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        log("native_compute_frame", "done", tMs = ms, extra = "frame=$frameIndex points=$result")
        return result
    }

    private fun logEngineStats(stats: EngineStats) {
        log(
            "engine_stats",
            "frame0",
            extra = "wallTimeMs=${stats.wallTimeMs} akazeRansacMs=${stats.akazeRansacMs} " +
                "hessianPrepassMs=${stats.hessianPrepassMs} delaunayMs=${stats.delaunayMs} " +
                "strainMs=${stats.strainMs} avgIcgnIterations=${stats.avgIcgnIterations} " +
                "avgThroughputPtsPerMs=${stats.avgThroughputPtsPerMs} convergencePercent=${stats.convergencePercent}",
        )
    }

    /** `run-as`-free space snapshot: the driver runs IN the app, so File.length() just works. */
    private fun duSummary(dir: File): String {
        if (!dir.isDirectory) return "bytes=0"
        val byExt = dir.walkTopDown().filter { it.isFile }.groupBy {
            when {
                it.name.endsWith(".dat") -> "dat"
                it.name == "reference.png" -> "reference"
                it.parentFile?.name == SessionPaths.RAW_DEFORMED_SUBDIR -> "raw_deformed"
                it.name.endsWith(".zip") -> "zip"
                else -> "other"
            }
        }
        val parts = byExt.entries.joinToString(" ") { (k, files) -> "$k=${files.sumOf { it.length() }}" }
        val total = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return "total=$total $parts"
    }

    /** One grep-able line per measurement: `SWD| op=<comp> phase=<phase> t_ms=<n> ...` */
    private fun log(component: String, phase: String, tMs: Double? = null, extra: String = "") {
        val tPart = tMs?.let { "t_ms=%.2f ".format(Locale.US, it) } ?: ""
        android.util.Log.i(TAG, "SWD| comp=$component phase=$phase $tPart$extra".trim())
    }

    private companion object {
        const val TAG = "SyntheticWorkloadDriver"
        const val POLL_INTERVAL_MS = 500L
        const val UPLOAD_TIMEOUT_MS = 10 * 60_000L
        const val RESTORE_TIMEOUT_MS = 10 * 60_000L
    }
}
