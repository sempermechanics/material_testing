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
    val referenceImage: Bitmap?,
    val referenceImageName: String,
    val deformedImageName: String,
    val fieldResults: List<FieldResult>,
    val engineStats: EngineStats
)

data class FieldResult(
    val fieldName: String,
    val fieldKey: String,
    val unit: String,
    val minValue: Float,
    val maxValue: Float,
    val meanValue: Float,
    val stdDevValue: Float,
    val minCoordX: Int,
    val minCoordY: Int,
    val maxCoordX: Int,
    val maxCoordY: Int,
    val bakedHeatmap: Bitmap
)

data class EngineStats(
    val totalPointsAttempted: Int,
    val totalPointsSolved: Int,
    val totalPointsRejected: Int,
    val pathAPoints: Int,
    val pathBPoints: Int,
    val simplexRescueTotal: Int,
    val simplexSavedCount: Int,
    val simplexDeadCount: Int,
    val avgIcgnIterations: Float,
    val wallTimeMs: Float,
    val akazeRansacMs: Float,
    val hessianPrepassMs: Float,
    val delaunayMs: Float,
    val strainMs: Float,
    val throughputPtsPerMs: Float,
    val convergencePercent: Float
)