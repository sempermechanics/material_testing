package com.rafad.indicvisiondic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Offline-first cloud sync against the inDIC GCP backend.
 *
 * Enqueued per frame after analysis with a network constraint. When online it:
 *  1. materialises the frame's artifacts — reference/deformed PNG (raw/),
 *     results CSV (csv/), PDF report (reports/), and a session metadata JSON
 *     (metadata/) — as local files and computes their size + SHA-256,
 *  2. POSTs /v1/sessions to create the Drive folder tree and one **resumable
 *     upload URI per file** (device-signed request),
 *  3. streams each file **directly to Google Drive** in resumable chunks —
 *     bytes never pass through the backend,
 *  4. POSTs /v1/files/{id}/complete to record each Drive pointer.
 *
 * See docs/CLOUD_ARCHITECTURE_GCP.md.
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

        val refPath = inputData.getString(DicKeys.REF_PATH) ?: return@withContext Result.failure()
        val defPath = inputData.getString(DicKeys.DEF_PATH) ?: ""
        val datPath = inputData.getString(DicKeys.DAT_PATH) ?: return@withContext Result.failure()
        val frameName = inputData.getString(DicKeys.FRAME_NAME) ?: "Frame"
        val specimen = inputData.getString(DicKeys.REF_NAME) ?: "Target"

        val temps = mutableListOf<File>()
        try {
            val artifacts = mutableListOf<Artifact>()

            // Session metadata (device, time, engine params, metrics) → metadata/.
            // Always generated so every session is self-describing for debugging.
            val metaFile = File(applicationContext.cacheDir, "upload_${frameName}_${System.currentTimeMillis()}.json")
            metaFile.writeText(buildMetadataJson(frameName, specimen))
            temps += metaFile
            artifacts += Artifact("metadata", "metadata_$frameName.json", metaFile)

            val refFile = File(refPath)
            if (refFile.exists() && refFile.length() > 0) {
                artifacts += Artifact("raw", "Reference.png", refFile)
            }
            val defFile = File(defPath)
            if (defFile.exists() && defFile.length() > 0) {
                artifacts += Artifact("raw", "Deformed_$frameName.png", defFile)
            }

            // Results CSV (from the .dat frame) and the PDF report.
            val datFile = File(datPath)
            var rawFloatData: FloatArray? = null
            if (datFile.exists()) {
                rawFloatData = DicResult.decodeDatBytes(datFile.readBytes())
                if (rawFloatData != null) {
                    val csvFile = File(applicationContext.cacheDir, "upload_${frameName}_${System.currentTimeMillis()}.csv")
                    writeCsv(rawFloatData, csvFile)
                    temps += csvFile
                    if (csvFile.length() > 0) artifacts += Artifact("csv", "Data_$frameName.csv", csvFile)
                }
            }
            if (rawFloatData != null && refFile.exists() && defFile.exists()) {
                val pdfFile = File(applicationContext.cacheDir, "upload_${frameName}_${System.currentTimeMillis()}.pdf")
                generatePdf(rawFloatData, refFile, defFile, frameName, specimen, pdfFile)
                temps += pdfFile
                if (pdfFile.length() > 0) artifacts += Artifact("reports", "Master_Report_$frameName.pdf", pdfFile)
            }

            if (artifacts.isEmpty()) {
                Timber.w("No artifacts to upload for %s", frameName)
                return@withContext Result.success()
            }

            // Manifest → create session + resumable targets.
            val specs = artifacts.map { FileSpecDto(it.name, it.role, it.file.length(), sha256(it.file)) }
            val metrics = mapOf(
                "pointsConverged" to inputData.getInt(DicKeys.POINTS_CONVERGED, 0).toFloat(),
                "avgIterations" to inputData.getFloat(DicKeys.AVG_ITERS, 0f),
                "executionTimeMs" to inputData.getInt(DicKeys.EXEC_TIME, 0).toFloat(),
            )
            val session = api.createSession(idToken, SessionCreateRequest(specimen, specs, metrics))
            if (session.uploads.size != artifacts.size) {
                Timber.e("Backend returned %d targets for %d files", session.uploads.size, artifacts.size)
                return@withContext Result.retry()
            }

            // Upload each artifact directly to Drive, then record the pointer.
            artifacts.forEachIndexed { i, art ->
                val target = session.uploads[i]
                Timber.d("Uploading %s (%d bytes)…", art.name, art.file.length())
                val (driveId, md5) = api.uploadResumable(target.uploadUrl, art.file, target.chunkSize)
                // Re-acquire a token in case a long upload outlived the previous one.
                val tk = TokenProvider.usableIdToken(applicationContext) ?: idToken
                api.completeFile(
                    tk, target.fileId,
                    FileCompleteRequest(session.sessionId, driveId, art.file.length(), md5),
                )
            }

            inputData.getString(DicKeys.SESSION_LOCAL_ID)?.let { localId ->
                SessionStore.markSynced(applicationContext, localId)
            }
            Timber.d("Upload complete for %s (session %s)", frameName, session.sessionId)
            Result.success()
        } catch (e: IndicApi.DeviceConflictException) {
            Timber.e("Device not authorized for this account — cannot upload")
            Result.failure()
        } catch (e: Exception) {
            Timber.e(e, "Upload failed for %s; will retry", frameName)
            Result.retry()
        } finally {
            temps.forEach { runCatching { it.delete() } }
        }
    }

    /** Human- and machine-readable session metadata for debugging/analysis. */
    private fun buildMetadataJson(frameName: String, specimen: String): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val roi = JSONObject()
            .put("x", inputData.getInt(DicKeys.ROI_X, 0))
            .put("y", inputData.getInt(DicKeys.ROI_Y, 0))
            .put("w", inputData.getInt(DicKeys.ROI_W, 0))
            .put("h", inputData.getInt(DicKeys.ROI_H, 0))
        return JSONObject()
            .put("schema", "indic.session.metadata/1")
            .put("specimen", specimen)
            .put("frame", frameName)
            .put("capturedAtUtc", iso)
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
                .put("subset", inputData.getInt(DicKeys.SUBSET, 0))
                .put("step", inputData.getInt(DicKeys.STEP, 0))
                .put("strainWindow", inputData.getInt(DicKeys.STRAIN_WIN, 0))
                .put("strainMethod", inputData.getString(DicKeys.STRAIN_METHOD) ?: "")
                .put("imageWidth", inputData.getInt(DicKeys.IMG_W, 0))
                .put("imageHeight", inputData.getInt(DicKeys.IMG_H, 0))
                .put("roi", roi))
            .put("metrics", JSONObject()
                .put("pointsConverged", inputData.getInt(DicKeys.POINTS_CONVERGED, 0))
                .put("avgIterations", inputData.getFloat(DicKeys.AVG_ITERS, 0f).toDouble())
                .put("executionTimeMs", inputData.getInt(DicKeys.EXEC_TIME, 0)))
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

    private suspend fun generatePdf(
        data: FloatArray,
        refFile: File,
        defFile: File,
        frameName: String,
        specimen: String,
        out: File,
    ) = withContext(Dispatchers.Default) {
        val imgW = inputData.getInt(DicKeys.IMG_W, 1000)
        val imgH = inputData.getInt(DicKeys.IMG_H, 1000)
        val step = inputData.getInt(DicKeys.STEP, 5)

        val originalBaseImg = BitmapFactory.decodeFile(refFile.absolutePath) ?: return@withContext
        val originalDefImg = BitmapFactory.decodeFile(defFile.absolutePath)

        val baseImg = Bitmap.createScaledBitmap(originalBaseImg, imgW, imgH, true)
        val defImg = Bitmap.createScaledBitmap(originalDefImg ?: originalBaseImg, imgW, imgH, true)

        val statsArray = inputData.getFloatArray(DicKeys.ENGINE_STATS) ?: FloatArray(16)
        val reportData = ReportBuilder.buildReport(
            ReportBuilder.ReportBuildParams(
                data = data,
                baseImg = baseImg,
                defImgForCover = defImg,
                imgW = imgW,
                imgH = imgH,
                step = step,
                sessionId = frameName,
                specimenName = specimen,
                analysisDate = ReportBuilder.currentAnalysisDate(),
                subsetSize = inputData.getInt(DicKeys.SUBSET, 41),
                strainWindow = inputData.getInt(DicKeys.STRAIN_WIN, 15),
                strainMethod = inputData.getString(DicKeys.STRAIN_METHOD) ?: "VSG",
                roiData = RoiData(
                    inputData.getInt(DicKeys.ROI_X, 0),
                    inputData.getInt(DicKeys.ROI_Y, 0),
                    inputData.getInt(DicKeys.ROI_W, imgW),
                    inputData.getInt(DicKeys.ROI_H, imgH),
                ),
                engineStats = EngineStats.fromArray(statsArray),
                referenceImageName = "Baseline",
                deformedImageName = frameName,
                drawMinMarker = false,
            ),
        )

        out.outputStream().use { stream ->
            PdfReportGenerator.generate(reportData, stream).collect { }
        }

        reportData.fieldResults.forEach { it.bakedHeatmap.recycle() }
        reportData.znssdHeatmap.recycle()
        baseImg.recycle()
        defImg.recycle()
        originalBaseImg.recycle()
        originalDefImg?.recycle()
    }
}
