package com.rafad.indicvisiondic

import android.graphics.Bitmap

interface ProgressCallback {
    fun onProgressUpdate(percentage: Int)
}

object IndicVisionNativeLib {

    init {
        System.loadLibrary("indicvision_core")
    }

    external fun getPreviewFromBytes(fileData: ByteArray, targetWidth: Int): Bitmap?

    external fun getImageDimensions(fileData: ByteArray): IntArray

    external fun analyzeRawBytes(
        refData: ByteArray, defData: ByteArray,
        roiX: Int, roiY: Int, subsetSize: Int,
        originalWidth: Int, originalHeight: Int
    ): FloatArray

    external fun computeLineProfile(
        refData: ByteArray,
        defData: ByteArray,
        startX: Int,
        endX: Int,
        y: Int,
        step: Int,
        subsetSize: Int,
        useReliabilityGuided: Boolean,
        useFeatureMatching: Boolean,
        callback: ProgressCallback
    ): FloatArray

    external fun computeFullField(
        refData: ByteArray,
        defData: ByteArray,
        rectX: Int,
        rectY: Int,
        rectWidth: Int,
        rectHeight: Int,
        step: Int,
        subsetSize: Int,
        strainWindow: Int,          // <--- NEW PARAMETER
        useReliabilityGuided: Boolean,
        useFeatureMatching: Boolean,
        callback: ProgressCallback
    ): FloatArray
}