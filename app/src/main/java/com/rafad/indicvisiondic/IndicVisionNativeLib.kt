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
// 2. Update this signature to use the interface
    external fun computeLineProfile(
        refData: ByteArray,
        defData: ByteArray,
        startX: Int,
        endX: Int,
        y: Int,
        step: Int,
        subsetSize: Int, interpolatorId: Int,
        callback: ProgressCallback // <--- CHANGED THIS
    ): FloatArray

}