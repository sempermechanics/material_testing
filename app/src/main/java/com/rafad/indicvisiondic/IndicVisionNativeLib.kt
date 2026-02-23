package com.rafad.indicvisiondic

import android.graphics.Bitmap

interface ProgressCallback {
    fun onProgressUpdate(percentage: Int)
}

object IndicVisionNativeLib {
    init {
        System.loadLibrary("indicvision_core")
    }

    external fun analyzeRawBytes(
        refData: ByteArray, defData: ByteArray,
        roiX: Int, roiY: Int, subsetSize: Int,
        originalWidth: Int, originalHeight: Int
    ): FloatArray

    // CRITICAL FIX: Ensure maskData is included so it matches C++ exactly
    external fun computeFullField(
        refData: ByteArray,
        defData: ByteArray,
        maskData: ByteArray, // FIXED: Added missing parameter
        rectX: Int,
        rectY: Int,
        rectWidth: Int,
        rectHeight: Int,
        step: Int,
        subsetSize: Int,
        strainWindow: Int,
        useReliabilityGuided: Boolean,
        useFeatureMatching: Boolean,
        applyBlur: Boolean,
        useNlvc: Boolean,
        callback: ProgressCallback
    ): FloatArray?

    external fun getPreviewFromBytes(imageBytes: ByteArray, maxDim: Int): android.graphics.Bitmap?

    external fun getImageDimensions(imageBytes: ByteArray): IntArray
} // <-- Only ONE closing brace here!