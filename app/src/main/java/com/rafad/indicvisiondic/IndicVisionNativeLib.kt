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

    external fun analyzeRawBytes(
        refData: ByteArray, defData: ByteArray,
        roiX: Int, roiY: Int, subsetSize: Int,
        originalWidth: Int, originalHeight: Int
    ): FloatArray

    // CRITICAL FIX: Ensure maskData is included so it matches C++ exactly
    external fun computeFullFieldDirect(
        refBytes: ByteArray, defBytes: ByteArray, maskBytes: ByteArray?,
        rectX: Int, rectY: Int, rectW: Int, rectH: Int,
        step: Int, subsetSize: Int, strainWindow: Int,
        useReliabilityGuided: Boolean, useFeatureMatching: Boolean,
        applyGaussianBlur: Boolean, useNlvcStrain: Boolean,
        outputBuffer: ByteBuffer, // Passes the shared memory block
        callbackObj: ProgressCallback?
    ): Int // Returns the number of valid points solved

    external fun getPreviewFromBytes(imageBytes: ByteArray, maxDim: Int): android.graphics.Bitmap?

    external fun getImageDimensions(imageBytes: ByteArray): IntArray
}