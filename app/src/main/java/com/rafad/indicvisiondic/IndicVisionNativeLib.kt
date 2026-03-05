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
    external fun initializeReference(refBytes: ByteArray, width: Int, height: Int, applyBlur: Boolean)
    external fun setDebugOutputDir(debugDir: String?)
    external fun analyzeRawBytes(
        refData: ByteArray, defData: ByteArray,
        roiX: Int, roiY: Int, subsetSize: Int,
        originalWidth: Int, originalHeight: Int
    ): FloatArray

    // CRITICAL FIX: Ensure maskData is included so it matches C++ exactly
    external fun computeFullFieldDirect(
        refBytes: ByteArray, defBytes: ByteArray, maskBytes: ByteArray?,
        rectX: Int, rectY: Int, rectWidth: Int, rectHeight: Int,
        step: Int, subsetSize: Int, strainWindow: Int,
        useDelaunay: Boolean,      // NEW: Controls Path A
        useFallback: Boolean,      // NEW: Controls safe fallback
        useRGDIC: Boolean,         // NEW: Forces Path B
        applyGaussianBlur: Boolean,
        useNlvcStrain: Boolean,
        outputBuffer: ByteBuffer,
        callback: ProgressCallback?
    ): Int

    external fun getPreviewFromBytes(imageBytes: ByteArray, maxDim: Int): android.graphics.Bitmap?

    external fun getImageDimensions(imageBytes: ByteArray): IntArray
}