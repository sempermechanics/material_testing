package com.rafad.indicvisiondic

import android.graphics.Bitmap
interface ProgressCallback {
    fun onProgressUpdate(percentage: Int)
}
object IndicVisionNativeLib {

    init {
        System.loadLibrary("indicvision_core")
    }

    /**
     * Sends raw file bytes to C++ to decode and return a displayable Bitmap.
     * This bypasses Android's weak TIFF support.
     */
    external fun getPreviewFromBytes(
        fileData: ByteArray,
        targetWidth: Int
    ): Bitmap?
    external fun getImageDimensions(fileData: ByteArray): IntArray
    /**
     * Analyze using raw file bytes (Robust for TIFF/PNG/JPG).
     */
    external fun analyzeRawBytes(
        refData: ByteArray, defData: ByteArray,
        roiX: Int, roiY: Int, subsetSize: Int,
        originalWidth: Int, originalHeight: Int,interpolatorId: Int
    ): FloatArray
    /**
     * Get the real dimensions of the image [width, height].
     */
// --- UPDATED SIGNATURE TO MATCH C++ ---
    external fun computeLineProfile(
        refData: ByteArray,
        defData: ByteArray,
        startX: Int,
        endX: Int,
        y: Int,
        step: Int,
        subsetSize: Int,
        interpolatorId: Int,       // 0=Bicubic, 1=Lanczos, 2=B-Spline
        useReliabilityGuided: Boolean, // NEW
        useFeatureMatching: Boolean,   // NEW
        callback: ProgressCallback
    ): FloatArray
    // --- NEW: 2D FULL FIELD ANALYSIS ---
    external fun computeFullField(
        refData: ByteArray,
        defData: ByteArray,
        rectX: Int,
        rectY: Int,
        rectWidth: Int,
        rectHeight: Int,
        step: Int,
        subsetSize: Int,
        interpolatorId: Int,
        useReliabilityGuided: Boolean,
        useFeatureMatching: Boolean,
        callback: ProgressCallback
    ): FloatArray
}
