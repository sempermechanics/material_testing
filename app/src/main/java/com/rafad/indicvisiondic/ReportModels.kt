package com.rafad.indicvisiondic

import android.graphics.Bitmap

data class ReportData(
    val sessionId: String,
    val specimenName: String,
    val analysisDate: String,
    val subsetSize: Int,
    val stepSize: Int,
    val strainWindow: Int,
    val strainMethod: String,

    // DOWN-SCALED IMAGES (300 DPI max)
    val referenceImage: Bitmap,
    val deformedImage: Bitmap,
    val referenceImageName: String,
    val deformedImageName: String,

    val fieldResults: List<FieldResult>,
    val engineStats: EngineStats,

    // NEW DIAGNOSTIC MAPS
    val znssdHeatmap: Bitmap,
    val solverPathMap: Bitmap,
    val globalAvgZnssd: Float
)

data class FieldResult(
    val fieldName: String,
    val fieldKey: String,
    val unit: String,
    val minValue: Float,
    val maxValue: Float,
    val meanValue: Float, // Simple Mean for Displacements, Mean Absolute for Strains
    val stdDevValue: Float,
    val minCoordX: Int,
    val minCoordY: Int,
    val maxCoordX: Int,
    val maxCoordY: Int,
    val bakedHeatmap: Bitmap // Down-scaled to 300 DPI
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
    val convergencePercent: Float
) {
    companion object {
        /** Matches the 16-element float[] written by IndicVisionJNI.cpp */
        fun fromArray(a: FloatArray): EngineStats =
            if (a.size >= 16) EngineStats(
                totalPointsAttempted = a[0].toInt(),
                totalPointsSolved    = a[1].toInt(),
                totalPointsRejected  = a[2].toInt(),
                pathAPoints          = a[3].toInt(),
                pathBPoints          = a[4].toInt(),
                simplexCalls         = a[5].toInt(),
                simplexSaved         = a[6].toInt(),
                finalDeadPoints      = a[7].toInt(),
                avgIcgnIterations    = a[8],
                wallTimeMs           = a[9],
                akazeRansacMs        = a[10],
                hessianPrepassMs     = a[11],
                delaunayMs           = a[12],
                strainMs             = a[13],
                avgThroughputPtsPerMs = a[14],
                convergencePercent   = a[15]
            ) else EngineStats(0,0,0,0,0,0,0,0,0f,0f,0f,0f,0f,0f,0f,0f)
    }
}