package com.rafad.indicvisiondic.report

import android.graphics.Bitmap

data class ReportData(
    val sessionId: String,
    val specimenName: String,
    val analysisDate: String,
    val subsetSize: Int,
    val stepSize: Int,
    val strainWindow: Int,
    val strainMethod: String,

    // Region of Interest Details
    val roiData: RoiData,

    // DOWN-SCALED IMAGES (300 DPI max) for the page-1 preview card
    val referenceImage: Bitmap,
    val deformedImage: Bitmap,
    val referenceImageName: String,
    val deformedImageName: String,

    val fieldResults: List<FieldResult>,
    val engineStats: EngineStats,

    // NEW DIAGNOSTIC MAPS
    val znssdHeatmap: Bitmap,
    val solverPathMap: Bitmap, // Future-proofing for path scatter plot
    val globalAvgZnssd: Float,

    /** Traceability, e.g. "v1.4 (12) • arm64-v8a". Null when unavailable. */
    val appBuild: String? = null,
)

// Dedicated Data Class for ROI
data class RoiData(
    val startX: Int,
    val startY: Int,
    val width: Int,
    val height: Int,
)

data class FieldResult(
    val fieldName: String,
    val fieldKey: String,
    val unit: String,
    val minValue: Float,
    val maxValue: Float,
    val meanValue: Float,
    val meanType: String, // "Simple Mean" or "Mean Absolute"
    val stdDevValue: Float,
    val minCoordX: Int,
    val minCoordY: Int,
    val maxCoordX: Int,
    val maxCoordY: Int,
    // Down-scaled to 300 DPI
    val bakedHeatmap: Bitmap,
)

data class EngineStats(
    val totalPointsAttempted: Int,
    val totalPointsSolved: Int,
    val totalPointsRejected: Int,
    val pathAPoints: Int,
    val pathBPoints: Int,

    // ONLY SIMPLEX (No RGDIC Ghost Fields)
    val simplexCalls: Int,
    val simplexSaved: Int,
    val finalDeadPoints: Int,

    val avgIcgnIterations: Float,
    val wallTimeMs: Float,
    val akazeRansacMs: Float,
    val hessianPrepassMs: Float,
    val delaunayMs: Float,
    val strainMs: Float,
    val avgThroughputPtsPerMs: Float,
    val convergencePercent: Float,

    /** 2 = full AKAZE mesh, 1 = sparse mesh, 0 = Path C fallback (RGDIC-only), -1 = unknown */
    val meshSeedingQuality: Int = MESH_SEEDING_UNKNOWN,
) {
    fun meshSeedingLabel(): String = when (meshSeedingQuality) {
        2 -> "Full AKAZE Mesh"
        1 -> "Sparse AKAZE Mesh"
        0 -> "Fallback (RGDIC-only, no mesh)"
        else -> "Unknown (legacy data)"
    }

    companion object {
        const val MESH_SEEDING_UNKNOWN = -1

        /** Matches the float[] written by IndicVisionJNI.cpp (16 slots + optional slot 16) */
        fun fromArray(a: FloatArray): EngineStats = if (a.size >= 16) {
            EngineStats(
                totalPointsAttempted = a[0].toInt(),
                totalPointsSolved = a[1].toInt(),
                totalPointsRejected = a[2].toInt(),
                pathAPoints = a[3].toInt(),
                pathBPoints = a[4].toInt(),
                simplexCalls = a[5].toInt(),
                simplexSaved = a[6].toInt(),
                finalDeadPoints = a[7].toInt(),
                avgIcgnIterations = a[8],
                wallTimeMs = a[9],
                akazeRansacMs = a[10],
                hessianPrepassMs = a[11],
                delaunayMs = a[12],
                strainMs = a[13],
                avgThroughputPtsPerMs = a[14],
                convergencePercent = a[15],
                meshSeedingQuality = if (a.size >= 17) a[16].toInt() else MESH_SEEDING_UNKNOWN,
            )
        } else {
            EngineStats(0, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }
}
