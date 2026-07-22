package com.rafad.indicvisiondic

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

interface ProgressCallback {
    fun onProgressUpdate(percentage: Int)
}

object IndicVisionNativeLib {
    init {
        System.loadLibrary("indicvision_core")
    }

    /**
     * NATIVE THREAD PINNING: A single persistent OS thread for ALL JNI/OpenMP calls.
     * OpenMP on Android is sensitive to being called from different threads, which
     * can cause internal assertion failures and SIGABRT.
     */
    val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "IndicVision-NativeThread").also { it.isDaemon = true }
    }

    val nativeDispatcher: CoroutineDispatcher = nativeExecutor.asCoroutineDispatcher()

    // Call this ONCE before a batch starts to cache the reference image
    // This stops the engine from rebuilding it 50 times and crashing the memory!
    external fun initializeReference(refBytes: ByteArray, maskBytes: ByteArray?, width: Int, height: Int, applyBlur: Boolean)
    external fun setDebugOutputDir(debugDir: String?)
    external fun analyzeRawBytes(
        refData: ByteArray,
        defData: ByteArray,
        roiX: Int,
        roiY: Int,
        subsetSize: Int,
        originalWidth: Int,
        originalHeight: Int,
    ): FloatArray

    external fun computeFullFieldDirect(
        refBytes: ByteArray,
        defBytes: ByteArray,
        maskData: ByteArray,
        roiX: Int,
        roiY: Int,
        roiW: Int,
        roiH: Int,
        step: Int,
        subset: Int,
        strainWin: Int,
        useZNCC: Boolean,
        useICGN: Boolean,
        useSpline: Boolean,
        applyBlur: Boolean,
        useNlvc: Boolean,
        // UI toggle selecting the Keys 6x6 interpolation kernel
        use6x6Interpolator: Boolean,
        outputBuffer: ByteBuffer,
        callback: ProgressCallback,
        // 17-slot engine telemetry (see EngineStats.fromArray)
        outMetrics: FloatArray,
    ): Int

    external fun getPreviewFromBytes(imageBytes: ByteArray, maxDim: Int): android.graphics.Bitmap?

    external fun getImageDimensions(imageBytes: ByteArray): IntArray
}
