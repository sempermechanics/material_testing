package com.rafad.indicvisiondic

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AnalysisViewModel : ViewModel() {

    // ✅ NATIVE THREAD PINNING: A single persistent OS thread for ALL JNI/OpenMP calls.
    val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "IndicVision-NativeThread").also { it.isDaemon = true }
    }

    override fun onCleared() {
        super.onCleared()
        nativeExecutor.shutdown()
    }

    var refBytes: ByteArray? = null
    var roiMaskBytes: ByteArray? = null
    var defFilePaths: List<String> = emptyList()

    var realRefWidth: Int = 0
    var realRefHeight: Int = 0
    var refName: String = "No image selected"

    val defCount: Int get() = defFilePaths.size
    val batchMode: Boolean get() = defFilePaths.size > 1

    var hasCustomRoi: Boolean = false
    var roiX: Int = 0
    var roiY: Int = 0
    var roiW: Int = 0
    var roiH: Int = 0

    var lastStep: Int = 5
    var lastBatchDirPath: String? = null
    var lastRefPath: String? = null
    var lastDefPath: String? = null
    var hasCompletedAnalysis: Boolean = false

    var currentSessionId: String? = null
    var engineStatsArray: FloatArray? = null

    var wizardStep: Int = 1
    var settingsReviewed: Boolean = false

    fun isReadyToCompute(): Boolean = refBytes != null && defFilePaths.isNotEmpty()

    fun getDefDisplayName(): String = when {
        defFilePaths.isEmpty() -> "No images selected"
        defFilePaths.size == 1 -> "Def: ${defFilePaths[0].substringAfterLast('/')}"
        else -> "${defFilePaths.size} images selected"
    }

    fun clearPreviousResults() {
        lastBatchDirPath = null
        lastRefPath = null
        lastDefPath = null
        hasCompletedAnalysis = false
    }

    data class BatchAnalysisParams(
        val cacheDir: File,
        val subset: Int,
        val step: Int,
        val strainWin: Int,
        val finalRectX: Int,
        val finalRectY: Int,
        val finalRectW: Int,
        val finalRectH: Int,
        val applyBlur: Boolean,
        val useNlvc: Boolean,
        val use6x6: Boolean,
        val maskData: ByteArray,
        val debugDir: File,
        val processingStartTime: Long,
    )

    data class BatchProgressUpdate(
        val percent: Int,
        val status: String,
        val timerText: String,
    )

    data class BatchAnalysisOutcome(
        val engineErrorCode: Int,
        val firstFrameValidPoints: Int,
        val totalFrames: Int,
        val executionTimeMs: Int,
        val batchDirPath: String,
    )

    /**
     * Full-field batch compute + offline upload queue. All JNI calls run on [nativeExecutor].
     */
    suspend fun runBatchAnalysis(
        appContext: Context,
        params: BatchAnalysisParams,
        onProgress: (BatchProgressUpdate) -> Unit,
    ): BatchAnalysisOutcome = withContext(nativeExecutor.asCoroutineDispatcher()) {
        val batchDir = File(params.cacheDir, "batch_results")
        if (!batchDir.exists()) batchDir.mkdirs()
        batchDir.listFiles()?.forEach { it.delete() }

        lastBatchDirPath = batchDir.absolutePath
        lastStep = params.step

        if (!params.debugDir.exists()) params.debugDir.mkdirs()
        IndicVisionNativeLib.setDebugOutputDir(params.debugDir.absolutePath)

        val totalFrames = defFilePaths.size
        val refBytes = refBytes ?: throw IllegalStateException("Reference missing")

        var firstFrameValidPoints = 0
        var firstFrameAvgIters = 0f
        var engineErrorCode = 0

        onProgress(BatchProgressUpdate(0, "Caching reference in engine…", "Caching Reference in Native Engine..."))
        IndicVisionNativeLib.initializeReference(
            refBytes, params.maskData, realRefWidth, realRefHeight, params.applyBlur
        )

        val gridW = params.finalRectW / params.step
        val gridH = params.finalRectH / params.step
        val maxPoints = gridW * gridH
        val outputBuffer = java.nio.ByteBuffer.allocateDirect(maxPoints * DicResult.BYTES_PER_POINT)
        outputBuffer.order(java.nio.ByteOrder.nativeOrder())

        for ((frameIndex, defPath) in defFilePaths.withIndex()) {
            val frameLabel = "Processing Frame ${frameIndex + 1}/$totalFrames..."
            onProgress(
                BatchProgressUpdate(
                    percent = ((frameIndex.toFloat() / totalFrames) * 100).toInt(),
                    status = if (totalFrames > 1) {
                        "Processing frame ${frameIndex + 1} of $totalFrames"
                    } else {
                        "Correlating & solving…"
                    },
                    timerText = frameLabel
                )
            )

            val defBytes = File(defPath).readBytes()
            val callback = object : ProgressCallback {
                override fun onProgressUpdate(percentage: Int) {
                    val frameProgress = (frameIndex.toFloat() / totalFrames) * 100
                    val overallProgress = frameProgress + (percentage.toFloat() / totalFrames)
                    onProgress(
                        BatchProgressUpdate(
                            percent = overallProgress.toInt(),
                            status = if (totalFrames > 1) {
                                "Processing frame ${frameIndex + 1} of $totalFrames"
                            } else {
                                "Correlating & solving…"
                            },
                            timerText = frameLabel
                        )
                    )
                }
            }

            outputBuffer.clear()
            val metricsCatcher = FloatArray(16)

            val validPointsCount = IndicVisionNativeLib.computeFullFieldDirect(
                refBytes, defBytes, params.maskData,
                params.finalRectX, params.finalRectY, params.finalRectW, params.finalRectH,
                params.step, params.subset, params.strainWin, true, true, false, params.applyBlur,
                params.useNlvc, params.use6x6,
                outputBuffer, callback, metricsCatcher
            )

            if (validPointsCount < 0) {
                engineErrorCode = validPointsCount
                break
            }

            if (frameIndex == 0) {
                firstFrameValidPoints = validPointsCount
                engineStatsArray = metricsCatcher.clone()
                firstFrameAvgIters = metricsCatcher[8]
            }

            if (validPointsCount == 0) continue

            val outputFile = File(batchDir, String.format("frame_%04d.dat", frameIndex))
            outputFile.outputStream().use { fos ->
                val bytes = ByteArray(validPointsCount * DicResult.BYTES_PER_POINT)
                outputBuffer.position(0)
                outputBuffer.get(bytes, 0, bytes.size)
                fos.write(bytes)
            }

            @Suppress("ExplicitGarbageCollectionCall")
            System.gc()
        }

        val executionTimeMs = (System.currentTimeMillis() - params.processingStartTime).toInt()

        if (firstFrameValidPoints > 0) {
            currentSessionId = "Pending_Cloud_Sync_" + UUID.randomUUID().toString().take(8)
            enqueueUploadWorkers(
                appContext, params, batchDir, refBytes,
                firstFrameValidPoints, firstFrameAvgIters, executionTimeMs
            )
        }

        BatchAnalysisOutcome(
            engineErrorCode = engineErrorCode,
            firstFrameValidPoints = firstFrameValidPoints,
            totalFrames = totalFrames,
            executionTimeMs = executionTimeMs,
            batchDirPath = batchDir.absolutePath
        )
    }

    private suspend fun enqueueUploadWorkers(
        appContext: Context,
        params: BatchAnalysisParams,
        batchDir: File,
        refBytes: ByteArray,
        firstFrameValidPoints: Int,
        firstFrameAvgIters: Float,
        executionTimeMs: Int,
    ) = withContext(Dispatchers.IO) {
        Log.d("inDIC_Diag", "========================================")
        Log.d("inDIC_Diag", "1. ENGINE FINISHED. PREPARING BATCH OFFLINE QUEUE.")

        var refBmp: Bitmap? = null
        try {
            refBmp = IndicVisionNativeLib.getPreviewFromBytes(refBytes, realRefWidth)
            val refPngFile = File(params.cacheDir, "temp_ref_${System.currentTimeMillis()}.png")
            refPngFile.outputStream().use { out ->
                refBmp?.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val generatedRefPath = refPngFile.absolutePath
            lastRefPath = generatedRefPath

            val currentUser = SupabaseManager.client.auth.currentUserOrNull()
            val userEmail = currentUser?.email ?: "Offline_User"
            val userId = currentUser?.id ?: "Offline_ID"

            for ((frameIndex, rawDefPath) in defFilePaths.withIndex()) {
                var defBmp: Bitmap? = null
                try {
                    var generatedDefPath = ""
                    val rawFile = File(rawDefPath)
                    if (rawFile.exists() && rawFile.length() < 50_000_000) {
                        val defBytes = rawFile.readBytes()
                        defBmp = IndicVisionNativeLib.getPreviewFromBytes(defBytes, realRefWidth)
                        val defPngFile = File(
                            params.cacheDir,
                            "temp_def_${System.currentTimeMillis()}_frame_$frameIndex.png"
                        )
                        defPngFile.outputStream().use { out ->
                            defBmp?.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        generatedDefPath = defPngFile.absolutePath
                    }

                    val datFile = File(batchDir, String.format("frame_%04d.dat", frameIndex))
                    val uploadData = Data.Builder()
                        .putString("USER_ID", userId)
                        .putString("USER_EMAIL", userEmail)
                        .putString("REF_PATH", generatedRefPath)
                        .putString("DEF_PATH", generatedDefPath)
                        .putString("DAT_PATH", datFile.absolutePath)
                        .putString("FRAME_NAME", "Frame_${frameIndex + 1}")
                        .putString("REF_NAME", refName.removePrefix("Ref: "))
                        .putInt("IMG_W", realRefWidth)
                        .putInt("IMG_H", realRefHeight)
                        .putInt("STEP", params.step)
                        .putInt("SUBSET", params.subset)
                        .putInt("STRAIN_WIN", params.strainWin)
                        .putString("STRAIN_METHOD", if (params.useNlvc) "NLVC" else "VSG")
                        .putInt("ROI_X", params.finalRectX)
                        .putInt("ROI_Y", params.finalRectY)
                        .putInt("ROI_W", params.finalRectW)
                        .putInt("ROI_H", params.finalRectH)
                        .putFloatArray("ENGINE_STATS", engineStatsArray ?: FloatArray(16))
                        .putInt("POINTS_CONVERGED", firstFrameValidPoints)
                        .putFloat("AVG_ITERS", firstFrameAvgIters)
                        .putInt("EXEC_TIME", executionTimeMs)
                        .build()

                    val uploadWork = OneTimeWorkRequestBuilder<DicUploadWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .setInputData(uploadData)
                        .build()

                    WorkManager.getInstance(appContext).enqueue(uploadWork)
                    Log.d("inDIC_Diag", "-> SUCCESS! Worker queued for Frame ${frameIndex + 1}.")
                } finally {
                    defBmp?.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e("inDIC_Diag", "❌ LOCAL CATCH: Failed to enqueue batch workers", e)
        } finally {
            refBmp?.recycle()
            Log.d("inDIC_Diag", "========================================")
        }
    }
}
