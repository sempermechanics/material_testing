package com.rafad.indicvisiondic

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Binary layout constants for native full-field `.dat` output (8 floats × 4 bytes per point). */
object DicResult {
    const val STRIDE = 8
    const val FLOATS_PER_POINT = 8
    const val BYTES_PER_POINT = 32

    const val IDX_X = 0
    const val IDX_Y = 1
    const val IDX_U = 2
    const val IDX_V = 3
    const val IDX_EXX = 4
    const val IDX_EYY = 5
    const val IDX_EXY = 6
    const val IDX_ZNSSD = 7

    const val MAX_ZNSSD = 0.15f
    const val STRAIN_TO_MILLISTRAIN = 1000f

    fun isValidDatBytes(bytes: ByteArray): Boolean =
        bytes.isNotEmpty() && bytes.size % BYTES_PER_POINT == 0

    fun decodeDatBytes(bytes: ByteArray): FloatArray? {
        if (!isValidDatBytes(bytes)) return null
        return FloatArray(bytes.size / 4).also { out ->
            ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(out)
        }
    }

    /**
     * The native engine writes a negative ZNSSD sentinel (CORR_INVALID = -1) for
     * skipped/failed points; every real solve has ZNSSD >= 0. Testing `>= 0`
     * instead of `!= 0` keeps a genuinely perfect match (ZNSSD == 0.0) valid.
     */
    fun isSolvedPoint(corr: Float): Boolean = corr >= 0f

    fun isAcceptedPoint(corr: Float, includeCorrelationField: Boolean = false): Boolean =
        if (includeCorrelationField) isSolvedPoint(corr) else isSolvedPoint(corr) && corr <= MAX_ZNSSD

    fun isStrainFieldIndex(dataIndex: Int): Boolean = dataIndex in IDX_EXX..IDX_EXY

    fun strainMultiplier(dataIndex: Int): Float =
        if (isStrainFieldIndex(dataIndex)) STRAIN_TO_MILLISTRAIN else 1f
}
