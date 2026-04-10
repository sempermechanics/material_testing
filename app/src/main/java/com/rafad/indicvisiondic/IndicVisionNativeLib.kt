package com.rafad.indicvisiondic

import android.graphics.Bitmap
import java.nio.ByteBuffer

interface ProgressCallback {
    fun onProgressUpdate(percentage: Int)
}

object IndicVisionNativeLib {
    init {
        System.loadLibrary("indicvision_core")
    }

    // 🚀 NEW FIX: Call this ONCE before a batch starts to cache the reference image
    // This stops the engine from rebuilding it 50 times and crashing the memory!
    external fun initializeReference(refBytes: ByteArray, maskBytes: ByteArray?, width: Int, height: Int, applyBlur: Boolean)
    external fun setDebugOutputDir(debugDir: String?)
    external fun analyzeRawBytes(
        refData: ByteArray, defData: ByteArray,
        roiX: Int, roiY: Int, subsetSize: Int,
        originalWidth: Int, originalHeight: Int
    ): FloatArray

    external fun computeFullFieldDirect(
        refBytes: ByteArray, defBytes: ByteArray, maskData: ByteArray,
        roiX: Int, roiY: Int, roiW: Int, roiH: Int,
        step: Int, subset: Int, strainWin: Int,
        useZNCC: Boolean, useICGN: Boolean, useSpline: Boolean, applyBlur: Boolean, useNlvc: Boolean,
        use6x6Interpolator: Boolean, // 🚀 NEW FIX: UI Toggle for the kernel
        outputBuffer: ByteBuffer, callback: ProgressCallback,
        outMetrics: FloatArray // <-- This must be here!
    ): Int

    external fun getPreviewFromBytes(imageBytes: ByteArray, maxDim: Int): android.graphics.Bitmap?

    external fun getImageDimensions(imageBytes: ByteArray): IntArray
}