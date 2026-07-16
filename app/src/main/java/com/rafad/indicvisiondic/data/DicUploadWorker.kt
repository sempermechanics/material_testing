package com.rafad.indicvisiondic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rafad.indicvisiondic.BuildConfig
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.data.net.FileCompleteRequest
import com.rafad.indicvisiondic.data.net.FileSpecDto
import com.rafad.indicvisiondic.data.net.IndicApi
import com.rafad.indicvisiondic.data.net.SessionCreateRequest
import com.rafad.indicvisiondic.data.net.TokenProvider
import com.rafad.indicvisiondic.data.net.TokenStore
import com.rafad.indicvisiondic.report.EngineStats
import com.rafad.indicvisiondic.report.PdfReportGenerator
import com.rafad.indicvisiondic.report.ReportBuilder
import com.rafad.indicvisiondic.report.RoiData
import com.rafad.indicvisiondic.report.VisualizationEngine
import com.rafad.indicvisiondic.ui.analysis.AnalysisViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Offline-first cloud sync against the inDIC GCP backend — **one backend session
 * per analysis** (not per frame).
 *
 * Enqueued once per analysis with a network constraint. It reads the whole
 * analysis from [SessionStore] (so only the session id travels through
 * WorkManager's small Data), materialises every artifact, then:
 *  1. POSTs /v1/sessions with the full manifest (creates the Drive folder tree
 *     and one resumable upload URI per file) — device-signed,
 *  2. streams each file **directly to Google Drive** in resumable chunks —
 *     bytes never pass through the backend,
 *  3. POSTs /v1/files/{id}/complete to record each Drive pointer.
 *
 * Layout per analysis:
 * ```
 * session/<sid>/raw/       Reference.png (once) + the original deformed images
 *               dat/       frame_%04d.dat   ← engine results; enables full restore
 *               csv/       Data_Frame_N.csv
 *               reports/   Master_Report_Frame_1.pdf (first frame only —
 *                          every other report is regenerable from the .dat)
 *               metadata/  metadata.json (device, time, engine params, frame list)
 * ```
 */
class DicUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private data class Artifact(val role: String, val name: String, val file: File)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val api = IndicApi(applicationContext)
        if (!api.enabled) {
            Timber.d("Cloud backend not configured — skipping upload")
            return@withContext Result.success()
        }
        val idToken = TokenProvider.usableIdToken(applicationContext)
        if (idToken == null) {
            Timber.w("No usable ID token yet — deferring upload")
            return@withContext Result.retry()
        }

        val localId = inputData.getString(DicKeys.SESSION_LOCAL_ID)
            ?: return@withContext Result.failure()
        val record = SessionStore.get(applicationContext, localId)
            ?: return@withContext Result.failure()

        val sessionDir = File(record.sessionDir)
        val rawDeformedDir = File(sessionDir, AnalysisViewModel.RAW_DEFORMED_SUBDIR)
        val temps = mutableListOf<File>()

        try {
            val artifacts = mutableListOf<Artifact>()

            // ── session-level metadata (once) ───────────────────────────────
            val metaFile = File(applicationContext.cacheDir, "upload_${localId}_metadata.json")
            metaFile.writeText(buildMetadataJson(record))
            temps += metaFile
            artifacts += Artifact("metadata", "metadata.json", metaFile)

            // ── reference image (once, not once per frame) ──────────────────
            val refFile = File(record.refPath)
            if (refFile.exists() && refFile.length() > 0) {
                artifacts += Artifact("raw", "Reference.png", refFile)
            }

            // ── per frame: original image, .dat, csv ────────────────────────
            record.defNames.forEachIndexed { index, defName ->
                val frameName = "Frame_${index + 1}"

                val defOriginal = File(rawDeformedDir, defName)
                if (defOriginal.exists() && defOriginal.length() > 0) {
                    artifacts += Artifact("raw", defName, defOriginal)
                } else {
                    Timber.w("Deformed image missing for %s: %s", frameName, defOriginal.absolutePath)
                }

                val datFile = File(sessionDir, String.format(Locale.US, "frame_%04d.dat", index))
                if (!datFile.exists()) {
                    Timber.w("No .dat for %s (%s) — skipping its csv/report", frameName, datFile.name)
                    return@forEachIndexed
                }
                artifacts += Artifact("dat", datFile.name, datFile)

                val data = DicResult.decodeDatBytes(datFile.readBytes()) ?: return@forEachIndexed

                val csvFile = File(applicationContext.cacheDir, "upload_${localId}_$frameName.csv")
                writeCsv(data, csvFile)
                temps += csvFile
                if (csvFile.length() > 0) artifacts += Artifact("csv", "Data_$frameName.csv", csvFile)
            }

            // ── per-frame reports, bundled into one archive ─────────────────
            // The classic single-frame report for every frame, zipped together:
            // one Drive item instead of N, while keeping each frame's report a
            // separate file you can pull out on its own.
            if (refFile.exists() && record.defNames.isNotEmpty()) {
                val zipFile = File(applicationContext.cacheDir, "upload_${localId}_reports.zip")
                val written = buildReportsZip(record, sessionDir, refFile, rawDeformedDir, zipFile)
                temps += zipFile
                if (written > 0 && zipFile.length() > 0) {
                    artifacts += Artifact("reports", "Reports.zip", zipFile)
                } else {
                    Timber.e("Reports zip NOT built for %s (reports=%d, bytes=%d)", localId, written, zipFile.length())
                }
            } else {
                Timber.e(
                    "Skipping reports for %s — reference exists=%s, frames=%d",
                    localId, refFile.exists(), record.defNames.size,
                )
            }

            if (artifacts.isEmpty()) {
                Timber.w("No artifacts to upload for %s", localId)
                return@withContext Result.success()
            }

            // ── manifest → one session for the whole analysis ───────────────
            Timber.i(
                "Uploading %s: %d files (%s)",
                localId,
                artifacts.size,
                artifacts.groupingBy { it.role }.eachCount(),
            )
            val specs = artifacts.map { FileSpecDto(it.name, it.role, it.file.length(), sha256(it.file)) }
            val metrics = mapOf(
                "pointsConverged" to record.pointsConverged.toFloat(),
                "avgIterations" to record.avgIterations,
                "executionTimeMs" to record.executionTimeMs.toFloat(),
                "frameCount" to record.frameCount.toFloat(),
            )
            val session = api.createSession(
                idToken,
                SessionCreateRequest(record.refName, specs, metrics, localSessionId = localId),
            )
            if (session.uploads.size != artifacts.size) {
                Timber.e("Backend returned %d targets for %d files", session.uploads.size, artifacts.size)
                return@withContext Result.retry()
            }
            // Remember the cloud id now — even a partial upload leaves data we
            // must be able to erase later.
            SessionStore.setCloudSessionId(applicationContext, localId, session.sessionId)

            // ── upload each artifact straight to Drive, then record it ──────
            artifacts.forEachIndexed { i, art ->
                val target = session.uploads[i]
                Timber.d("Uploading %s (%d bytes)…", art.name, art.file.length())
                val (driveId, md5) = api.uploadResumable(target.uploadUrl, art.file, target.chunkSize)
                val tk = TokenProvider.usableIdToken(applicationContext) ?: idToken
                api.completeFile(
                    tk, target.fileId,
                    FileCompleteRequest(session.sessionId, driveId, art.file.length(), md5),
                )
                setProgress(androidx.work.workDataOf("done" to i + 1, "total" to artifacts.size))
            }

            SessionStore.markSynced(applicationContext, localId)
            Timber.d("Upload complete for %s (%d files, session %s)", localId, artifacts.size, session.sessionId)
            Result.success()
        } catch (e: IndicApi.DeviceNotActiveException) {
            // The server has no ACTIVE device record for us (revoked/reset) while
            // our local "registered" flag said otherwise. Re-register and retry
            // instead of stalling forever.
            Timber.w("Device not active server-side — re-registering and retrying")
            TokenStore.setDeviceRegistered(applicationContext, false)
            runCatching { api.registerDevice(idToken) }
                .onSuccess { TokenStore.setDeviceRegistered(applicationContext, true) }
                .onFailure { Timber.e(it, "Re-registration failed") }
            Result.retry()
        } catch (e: IndicApi.DeviceConflictException) {
            Timber.e("This account is bound to a different device — cannot upload")
            SessionStore.setSyncState(applicationContext, localId, SessionRecord.SyncState.FAILED)
            Result.failure()
        } catch (e: IndicApi.ApiException) {
            // 409 = analysis quota reached, 413 = too many files: retrying won't help,
            // so mark it FAILED instead of failing silently.
            if (e.code == 409 || e.code == 413) {
                Timber.e("Upload rejected (%d): %s", e.code, e.detail)
                SessionStore.setSyncState(applicationContext, localId, SessionRecord.SyncState.FAILED)
                Result.failure()
            } else {
                Timber.e(e, "Upload failed for %s; will retry", localId)
                Result.retry()
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.e(e, "Upload failed for %s; will retry", localId)
            Result.retry()
        } finally {
            temps.forEach { runCatching { it.delete() } }
        }
    }

    /** Session-level metadata: device, time, engine params, and the frame list. */
    private fun buildMetadataJson(record: SessionRecord): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val frames = JSONArray()
        record.defNames.forEachIndexed { index, name ->
            frames.put(
                JSONObject()
                    .put("index", index)
                    .put("frame", "Frame_${index + 1}")
                    .put("image", name)
                    .put("dat", String.format(Locale.US, "frame_%04d.dat", index))
                    .put("csv", "Data_Frame_${index + 1}.csv"),
            )
        }
        return JSONObject()
            .put("schema", "indic.session.metadata/2")
            .put("localSessionId", record.id)
            .put("name", record.name)
            .put("specimen", record.refName)
            .put("capturedAtUtc", iso)
            .put("frameCount", record.frameCount)
            .put("frames", frames)
            .put("app", JSONObject()
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE))
            .put("device", JSONObject()
                .put("id", DeviceKeyManager(applicationContext).getDeviceId())
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("os", "Android ${Build.VERSION.RELEASE}")
                .put("sdkInt", Build.VERSION.SDK_INT))
            .put("user", JSONObject()
                .put("uid", TokenStore.cachedUid(applicationContext))
                .put("email", TokenStore.cachedEmail(applicationContext)))
            .put("engine", JSONObject()
                .put("subset", record.subset)
                .put("step", record.step)
                .put("strainWindow", record.strainWindow)
                .put("strainMethod", record.strainMethod)
                .put("use6x6", record.use6x6)
                .put("imageWidth", record.imgW)
                .put("imageHeight", record.imgH)
                .put("roi", JSONObject()
                    .put("x", record.roiX).put("y", record.roiY)
                    .put("w", record.roiW).put("h", record.roiH))
                .put("stats", JSONArray(record.engineStats)))
            .put("metrics", JSONObject()
                .put("pointsConverged", record.pointsConverged)
                .put("avgIterations", record.avgIterations.toDouble())
                .put("executionTimeMs", record.executionTimeMs))
            .toString(2)
    }

    private fun writeCsv(data: FloatArray, out: File) {
        out.bufferedWriter().use { w ->
            w.write("X,Y,U_Displacement,V_Displacement,Exx_Strain,Eyy_Strain,Exy_Shear,Correlation\n")
            var i = 0
            while (i < data.size) {
                if (DicResult.isSolvedPoint(data[i + DicResult.IDX_ZNSSD])) {
                    w.write(DicResult.csvRow(data, i))
                    w.write("\n")
                }
                i += DicResult.STRIDE
            }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The whole-analysis PDF: a cover built from frame 1 plus one chapter per
     * frame (annotated Exx heatmap + a five-field stats table) — the same
     * artifact the app produces for "share all frames". Chapters are built
     * lazily by [PdfReportGenerator.generateBatch] and recycled page-by-page,
     * so memory stays flat regardless of frame count.
     *
     * Returns false when the report could not be built (e.g. unreadable frame 1).
     */
    @Suppress("ReturnCount")
    private suspend fun generateBatchReport(
        record: SessionRecord,
        sessionDir: File,
        refFile: File,
        rawDeformedDir: File,
        out: File,
    ): Boolean = withContext(Dispatchers.Default) {
        val imgW = record.imgW
        val imgH = record.imgH
        if (imgW <= 0 || imgH <= 0) return@withContext false

        val firstDat = File(sessionDir, String.format(Locale.US, "frame_%04d.dat", 0))
        if (!firstDat.exists()) return@withContext false
        val firstData = DicResult.decodeDatBytes(firstDat.readBytes()) ?: return@withContext false

        val originalBaseImg = BitmapFactory.decodeFile(refFile.absolutePath) ?: return@withContext false
        val baseImg = Bitmap.createScaledBitmap(originalBaseImg, imgW, imgH, true)
        val firstDefFile = File(rawDeformedDir, record.defNames.firstOrNull().orEmpty())
        val originalDefImg = BitmapFactory.decodeFile(firstDefFile.absolutePath)
        val defImg = Bitmap.createScaledBitmap(originalDefImg ?: originalBaseImg, imgW, imgH, true)

        val statsArray = FloatArray(ENGINE_STATS_SIZE) { record.engineStats.getOrElse(it) { 0f } }
        val cover = ReportBuilder.buildReport(
            ReportBuilder.ReportBuildParams(
                data = firstData,
                baseImg = baseImg,
                defImgForCover = defImg,
                imgW = imgW,
                imgH = imgH,
                step = record.step,
                sessionId = record.id,
                specimenName = record.refName,
                analysisDate = ReportBuilder.currentAnalysisDate(),
                subsetSize = record.subset,
                strainWindow = record.strainWindow,
                strainMethod = record.strainMethod.ifBlank { "VSG" },
                roiData = RoiData(record.roiX, record.roiY, record.roiW, record.roiH),
                engineStats = EngineStats.fromArray(statsArray),
                referenceImageName = "Baseline",
                deformedImageName = record.defNames.firstOrNull() ?: "Frame_1",
                drawMinMarker = false,
            ),
        )

        var ok = true
        try {
            out.outputStream().use { stream ->
                PdfReportGenerator.generateBatch(
                    cover = cover,
                    frameCount = record.defNames.size,
                    chapterAt = { index -> buildChapter(record, sessionDir, baseImg, index) },
                    outputStream = stream,
                ).collect { progress ->
                    // generateBatch surfaces failures as a Flow event rather than
                    // throwing — don't hand back a half-written PDF.
                    if (progress is PdfReportGenerator.Progress.Error) {
                        Timber.e(progress.ex, "Batch report failed")
                        ok = false
                    }
                }
            }
        } finally {
            cover.fieldResults.forEach { it.bakedHeatmap.recycle() }
            cover.znssdHeatmap.recycle()
            defImg.recycle()
            originalDefImg?.recycle()
            baseImg.recycle()
            originalBaseImg.recycle()
        }
        ok
    }

    /** One frame's chapter: annotated heatmap over the reference + stats table. */
    private fun buildChapter(
        record: SessionRecord,
        sessionDir: File,
        baseImg: Bitmap,
        index: Int,
    ): PdfReportGenerator.FrameChapter {
        val title = record.defNames.getOrNull(index) ?: "Frame_${index + 1}"
        val datFile = File(sessionDir, String.format(Locale.US, "frame_%04d.dat", index))
        val data = (if (datFile.exists()) DicResult.decodeDatBytes(datFile.readBytes()) else null)
            ?: return PdfReportGenerator.FrameChapter("$title (unreadable)", null, emptyList())

        val image = renderAnnotated(record, baseImg, data, CHAPTER_FIELD_INDEX, CHAPTER_FIELD_LABEL)
        val rows = CHAPTER_FIELDS.map { (label, idx) ->
            val stats = DicResult.fieldStats(data, idx) ?: floatArrayOf(0f, 0f, 0f)
            val unit = if (DicResult.isStrainFieldIndex(idx)) "mε" else "px"
            listOf(
                "$label [$unit]",
                ReportBuilder.formatMetric(stats[0]),
                ReportBuilder.formatMetric(stats[1]),
                ReportBuilder.formatMetric(stats[2]),
            )
        }
        return PdfReportGenerator.FrameChapter(title, image, rows)
    }

    /** Heatmap of one field baked over the reference image, with annotations. */
    private fun renderAnnotated(
        record: SessionRecord,
        baseImg: Bitmap,
        data: FloatArray,
        dataIndex: Int,
        typeString: String,
    ): Bitmap {
        val (heatmap, actualMin, actualMax) = VisualizationEngine.generateHeatmap(
            data, record.imgW, record.imgH, dataIndex, record.step, null, null,
        )
        val extrema = ReportBuilder.computeFieldExtrema(data, dataIndex)
        val out = Bitmap.createBitmap(record.imgW, record.imgH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(baseImg, 0f, 0f, null)
        canvas.drawBitmap(heatmap, 0f, 0f, Paint().apply { alpha = HEATMAP_ALPHA })
        val unit = if (DicResult.isStrainFieldIndex(dataIndex)) "mε" else "px"
        ReportBuilder.bakeAnnotationsToCanvas(
            canvas, record.imgW, record.imgH, actualMin, actualMax,
            typeString, unit, extrema.maxIdx, extrema.minIdx, data,
        )
        heatmap.recycle()
        return out
    }

    private companion object {
        const val ENGINE_STATS_SIZE = 16
        const val HEATMAP_ALPHA = 180

        /** Field shown as each chapter's heatmap. */
        const val CHAPTER_FIELD_INDEX = DicResult.IDX_EXX
        const val CHAPTER_FIELD_LABEL = "Exx"

        val CHAPTER_FIELDS = listOf(
            "U" to DicResult.IDX_U,
            "V" to DicResult.IDX_V,
            "Exx" to DicResult.IDX_EXX,
            "Eyy" to DicResult.IDX_EYY,
            "Exy" to DicResult.IDX_EXY,
        )
    }
}
