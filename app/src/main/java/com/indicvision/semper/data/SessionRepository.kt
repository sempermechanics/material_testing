@file:Suppress("TooGenericExceptionCaught") // corrupt/OOM decode must never abort session save

package com.indicvision.semper.data

import android.content.Context
import android.graphics.Bitmap
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.imaging.ImageEncode
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.VisualizationEngine
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session on-disk persistence helpers extracted from [com.indicvision.semper.ui.analysis.AnalysisViewModel].
 * Keeps file/index bookkeeping off the ViewModel surface.
 */
class SessionRepository {

    /** Writes a display-sized PNG of the reference into the session dir. */
    fun writeReferenceCopy(sessionDir: File, refBytes: ByteArray): String {
        val refPngFile = File(sessionDir, "reference.png")
        var refBmp: Bitmap? = null
        try {
            refBmp = SemperNativeLib.getPreviewFromBytes(
                refBytes,
                VisualizationEngine.DISPLAY_MAX_EDGE,
            ) ?: BitmapDecode.decodeByteArrayCapped(refBytes)
            val bmp = refBmp
            if (bmp != null) {
                refPngFile.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, out)
                }
            } else {
                refPngFile.writeBytes(refBytes)
            }
        } catch (e: Exception) {
            Timber.w(e, "Reference preview failed; storing the raw reference bytes")
            runCatching { refPngFile.writeBytes(refBytes) }
        } finally {
            refBmp?.recycle()
        }
        return refPngFile.absolutePath
    }

    /** Copies one deformed original into the session dir; returns its file name. */
    fun copyRawDeformed(
        batchDir: File,
        frameIndex: Int,
        defFilePaths: List<String>,
        defOriginalNames: List<String>,
    ): String {
        val rawDir = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val name = (defOriginalNames.getOrNull(frameIndex) ?: File(defFilePaths[frameIndex]).name)
            .substringAfterLast('/')
            .substringAfterLast('\\')
        return runCatching {
            val target = File(rawDir, name)
            if (target.exists()) target.delete()
            FileInputStream(File(defFilePaths[frameIndex])).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target.name
        }.onFailure { Timber.w(it, "Could not persist the sweep's deformed frame") }.getOrDefault("")
    }

    fun defaultSessionName(refFileName: String, now: Long): String {
        val base = refFileName.substringBeforeLast('.').ifBlank { "Analysis" }
        val stamp = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(now))
        return "$base · $stamp"
    }

    @Suppress("LongParameterList")
    fun buildSessionRecord(
        appContext: Context,
        localSessionId: String,
        batchDir: File,
        refPngPath: String,
        refName: String,
        realRefWidth: Int,
        realRefHeight: Int,
        settings: SessionRecordSettings,
        cloudEnabled: Boolean,
        pointsConverged: Int,
        avgIterations: Float,
        executionTimeMs: Int,
        frameCount: Int,
        defNames: List<String>,
        engineStatsArray: FloatArray?,
        stopCode: Int = 0,
        plannedFrameCount: Int = 0,
    ): SessionRecord {
        val now = System.currentTimeMillis()
        val existing = SessionStore.get(appContext, localSessionId)
        val convergence = engineStatsArray?.getOrNull(EngineStats.SLOT_CONVERGENCE) ?: 0f
        return SessionRecord(
            id = localSessionId,
            name = existing?.name ?: defaultSessionName(refName, now),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            frameCount = frameCount,
            subset = settings.subset,
            step = settings.step,
            strainWindow = settings.strainWin,
            use6x6 = settings.use6x6,
            imgW = realRefWidth,
            imgH = realRefHeight,
            roiX = settings.roiX,
            roiY = settings.roiY,
            roiW = settings.roiW,
            roiH = settings.roiH,
            refPath = refPngPath,
            refName = refName,
            sessionDir = batchDir.absolutePath,
            defNames = defNames,
            headline = String.format(Locale.US, "%.1f%% converged", convergence),
            engineStats = engineStatsArray?.toList() ?: emptyList(),
            stopCode = stopCode,
            plannedFrameCount = plannedFrameCount,
            strainMethod = "VSG",
            pointsConverged = pointsConverged,
            avgIterations = avgIterations,
            executionTimeMs = executionTimeMs,
            syncState = if (cloudEnabled) SessionRecord.SyncState.PENDING else SessionRecord.SyncState.LOCAL_ONLY,
        )
    }

    /**
     * Persist [record] via [SessionStore.upsert] and optionally enqueue a cloud
     * upload. Returns the upsert result (false = quota refuse / corrupt index).
     */
    fun saveSession(
        context: Context,
        record: SessionRecord,
        enqueueCloudIfSaved: Boolean = false,
    ): Boolean {
        val saved = SessionStore.upsert(context, record)
        if (saved && enqueueCloudIfSaved) {
            CloudSync.enqueueUpload(context, record.id)
        }
        return saved
    }
}

/** Geometry + subset settings captured into a [SessionRecord]. */
data class SessionRecordSettings(
    val subset: Int,
    val step: Int,
    val strainWin: Int,
    val roiX: Int,
    val roiY: Int,
    val roiW: Int,
    val roiH: Int,
    val use6x6: Boolean,
)
