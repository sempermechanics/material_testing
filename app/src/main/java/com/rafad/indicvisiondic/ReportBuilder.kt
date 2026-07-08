package com.rafad.indicvisiondic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Headless report assembly shared by [ResultViewerActivity] and [DicUploadWorker].
 * No Activity or UI dependencies.
 */
object ReportBuilder {

    private val FIELD_NAMES = listOf(
        "U Displacement", "V Displacement", "Exx Strain", "Eyy Strain", "Exy Shear",
        "ZNSSD (Correlation Quality)"
    )
    private val FIELD_KEYS = listOf("U", "V", "Exx", "Eyy", "Exy", "ZNSSD")

    data class FieldExtrema(val maxIdx: Int, val minIdx: Int)

    data class ReportBuildParams(
        val data: FloatArray,
        val baseImg: Bitmap,
        val defImgForCover: Bitmap,
        val imgW: Int,
        val imgH: Int,
        val step: Int,
        val sessionId: String,
        val specimenName: String,
        val analysisDate: String,
        val subsetSize: Int,
        val strainWindow: Int,
        val strainMethod: String,
        val roiData: RoiData,
        val engineStats: EngineStats,
        val referenceImageName: String,
        val deformedImageName: String,
        /** At 940e57d cloud worker PDFs drew MAX only; viewer PDFs drew MAX+MIN. */
        val drawMinMarker: Boolean = true,
    )

    fun formatMetric(value: Float): String {
        val absVal = abs(value)
        return if (absVal > 0f && (absVal < 0.001f || absVal >= 10000f)) {
            String.format(Locale.US, "%.2e", value)
        } else {
            String.format(Locale.US, "%.5f", value)
        }
    }

    fun Bitmap.compressForPdf(maxWidth: Int = 600): Bitmap {
        val ratio = maxWidth.toFloat() / width
        val newWidth = if (width > maxWidth) maxWidth else width
        val newHeight = (height * ratio).toInt()

        val scaled = Bitmap.createScaledBitmap(this, newWidth, newHeight, true)
        val strippedBmp = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.RGB_565)
        Canvas(strippedBmp).drawBitmap(scaled, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        if (scaled != this) scaled.recycle()
        return strippedBmp
    }

    /** Percentile-clamped global max/min indices for one field column. */
    fun computeFieldExtrema(
        data: FloatArray,
        dataIndex: Int,
        absoluteStrainValues: Boolean = true,
    ): FieldExtrema {
        val isStrain = DicResult.isStrainFieldIndex(dataIndex)
        val isCorrelation = dataIndex == DicResult.IDX_ZNSSD

        var maxV = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxIdx = -1
        var minIdx = -1
        val validValues = mutableListOf<Float>()

        fun fieldValue(rawVal: Float): Float =
            if (isStrain && absoluteStrainValues) abs(rawVal) else rawVal

        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr, isCorrelation)) {
                validValues.add(fieldValue(data[i + dataIndex]))
            }
        }
        if (validValues.isEmpty()) return FieldExtrema(-1, -1)

        validValues.sort()
        val p02 = validValues[(validValues.size * 0.02).toInt().coerceIn(0, validValues.size - 1)]
        val p98 = validValues[(validValues.size * 0.98).toInt().coerceIn(0, validValues.size - 1)]

        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr, isCorrelation)) {
                val valToCheck = fieldValue(data[i + dataIndex])
                if (valToCheck in p02..p98) {
                    if (valToCheck > maxV) { maxV = valToCheck; maxIdx = i }
                    if (valToCheck < minV) { minV = valToCheck; minIdx = i }
                }
            }
        }

        if (maxIdx == -1 || minIdx == -1) {
            for (i in data.indices step DicResult.STRIDE) {
                val corr = data[i + DicResult.IDX_ZNSSD]
                if (DicResult.isAcceptedPoint(corr, isCorrelation)) {
                    val valToCheck = fieldValue(data[i + dataIndex])
                    if (valToCheck > maxV) { maxV = valToCheck; maxIdx = i }
                    if (valToCheck < minV) { minV = valToCheck; minIdx = i }
                }
            }
        }

        return FieldExtrema(maxIdx, minIdx)
    }

    fun computeGlobalAvgZnssd(data: FloatArray): Float {
        var total = 0f
        var count = 0
        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr)) {
                total += corr
                count++
            }
        }
        return if (count > 0) total / count else 0f
    }

    fun buildReport(params: ReportBuildParams): ReportData {
        val data = params.data
        val baseImg = params.baseImg
        val fieldResults = mutableListOf<FieldResult>()
        var correlationHeatmap: Bitmap? = null

        for (fieldIndex in FIELD_NAMES.indices) {
            val dataIndex = fieldIndex + DicResult.IDX_U
            val isStrain = DicResult.isStrainFieldIndex(dataIndex)
            val isCorrelation = dataIndex == DicResult.IDX_ZNSSD
            val multiplier = DicResult.strainMultiplier(dataIndex)
            val unit = when {
                isStrain -> "mε"
                isCorrelation -> ""
                else -> "px"
            }
            val meanTypeString = if (isStrain) "Mean Absolute" else "Simple Mean"

            val validValues = mutableListOf<Float>()
            for (i in data.indices step DicResult.STRIDE) {
                val corr = data[i + DicResult.IDX_ZNSSD]
                if (DicResult.isAcceptedPoint(corr, isCorrelation)) {
                    val rawVal = data[i + dataIndex]
                    validValues.add(if (isStrain) abs(rawVal) else rawVal)
                }
            }
            if (validValues.isEmpty()) continue

            val extrema = computeFieldExtrema(data, dataIndex, absoluteStrainValues = false)
            val mean = validValues.average().toFloat()
            val stdDev = sqrt(validValues.map { (it - mean) * (it - mean) }.average()).toFloat()

            val (heatmapBmp, actualMin, actualMax) = VisualizationEngine.generateHeatmap(
                data, params.imgW, params.imgH, dataIndex, params.step, null, null
            )

            val bakedHeatmap = Bitmap.createBitmap(params.imgW, params.imgH, Bitmap.Config.ARGB_8888).also { bmp ->
                val tempCanvas = Canvas(bmp)
                tempCanvas.drawBitmap(baseImg, 0f, 0f, null)
                tempCanvas.drawBitmap(heatmapBmp, 0f, 0f, Paint().apply { alpha = 180 })
                bakeAnnotationsToCanvas(
                    tempCanvas, params.imgW, params.imgH, actualMin, actualMax,
                    FIELD_KEYS[fieldIndex], unit, extrema.maxIdx, extrema.minIdx, data,
                    drawMinMarker = params.drawMinMarker,
                )
            }.compressForPdf()

            heatmapBmp.recycle()

            if (isCorrelation) {
                correlationHeatmap = bakedHeatmap
            } else {
                fieldResults.add(
                    FieldResult(
                        fieldName = FIELD_NAMES[fieldIndex],
                        fieldKey = FIELD_KEYS[fieldIndex],
                        unit = unit,
                        minValue = actualMin * multiplier,
                        maxValue = actualMax * multiplier,
                        meanValue = mean * multiplier,
                        stdDevValue = stdDev * multiplier,
                        meanType = meanTypeString,
                        minCoordX = data[extrema.minIdx].toInt(),
                        minCoordY = data[extrema.minIdx + 1].toInt(),
                        maxCoordX = data[extrema.maxIdx].toInt(),
                        maxCoordY = data[extrema.maxIdx + 1].toInt(),
                        bakedHeatmap = bakedHeatmap
                    )
                )
            }
        }

        return ReportData(
            sessionId = params.sessionId,
            specimenName = params.specimenName,
            analysisDate = params.analysisDate,
            subsetSize = params.subsetSize,
            stepSize = params.step,
            strainWindow = params.strainWindow,
            strainMethod = params.strainMethod,
            roiData = params.roiData,
            referenceImage = baseImg.compressForPdf(),
            deformedImage = params.defImgForCover.compressForPdf(),
            referenceImageName = params.referenceImageName,
            deformedImageName = params.deformedImageName,
            fieldResults = fieldResults,
            engineStats = params.engineStats,
            znssdHeatmap = correlationHeatmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            solverPathMap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            globalAvgZnssd = computeGlobalAvgZnssd(data)
        )
    }

    fun currentAnalysisDate(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    fun bakeAnnotationsToCanvas(
        canvas: Canvas,
        width: Int,
        height: Int,
        minValRaw: Float,
        maxValRaw: Float,
        typeString: String,
        unit: String,
        maxIdx: Int,
        minIdx: Int,
        dataArray: FloatArray,
        drawMinMarker: Boolean = true,
    ) {
        val multiplier = if (unit == "mε") DicResult.STRAIN_TO_MILLISTRAIN else 1f
        val maxVal = maxValRaw * multiplier
        val minVal = minValRaw * multiplier

        val textSize = width * 0.025f
        val padding = width * 0.02f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        val bgPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }

        val infoText = arrayOf(
            "inDIC Analysis Report",
            "Field: $typeString [$unit]",
            "Max: ${formatMetric(maxVal)}",
            "Min: ${formatMetric(minVal)}"
        )
        var maxTextWidth = 0f
        for (line in infoText) {
            val w = textPaint.measureText(line)
            if (w > maxTextWidth) maxTextWidth = w
        }

        canvas.drawRect(
            padding * 0.5f, padding * 0.5f,
            padding * 1.5f + maxTextWidth,
            padding + (infoText.size * (textSize * 1.4f)) + padding,
            bgPaint
        )
        var currentY = padding + textSize
        for (line in infoText) {
            canvas.drawText(line, padding, currentY, textPaint)
            currentY += textSize * 1.4f
        }

        val barWidth = width * 0.03f
        val barHeight = height * 0.5f
        val barLeft = width - padding - barWidth - (textSize * 4.5f)
        val barTop = (height - barHeight) / 2f
        val barRight = barLeft + barWidth
        val barBottom = barTop + barHeight

        val jetColors = intArrayOf(
            Color.rgb(127, 0, 0), Color.rgb(255, 0, 0), Color.rgb(255, 255, 0),
            Color.rgb(0, 255, 255), Color.rgb(0, 0, 255), Color.rgb(0, 0, 127)
        )
        canvas.drawRect(
            barLeft, barTop, barRight, barBottom,
            Paint().apply {
                shader = LinearGradient(0f, barTop, 0f, barBottom, jetColors, null, Shader.TileMode.CLAMP)
            }
        )
        canvas.drawRect(
            barLeft, barTop, barRight, barBottom,
            Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        )

        val scaleTextPaint = Paint(textPaint).apply {
            textAlign = Paint.Align.LEFT
            clearShadowLayer()
            color = Color.BLACK
        }
        val whiteBgPaint = Paint().apply { color = Color.argb(200, 255, 255, 255) }
        fun drawScaleLabel(text: String, y: Float) {
            val w = scaleTextPaint.measureText(text)
            canvas.drawRect(
                barRight + padding * 0.5f - 5f, y - textSize,
                barRight + padding * 0.5f + w + 5f, y + (textSize * 0.3f),
                whiteBgPaint
            )
            canvas.drawText(text, barRight + padding * 0.5f, y, scaleTextPaint)
        }

        drawScaleLabel(formatMetric(maxVal), barTop + (textSize * 0.3f))
        drawScaleLabel(formatMetric((maxVal + minVal) / 2f), barTop + (barHeight / 2f) + (textSize * 0.3f))
        drawScaleLabel(formatMetric(minVal), barBottom)

        if (maxIdx != -1 && minIdx != -1) {
            val maxX = dataArray[maxIdx]
            val maxY = dataArray[maxIdx + 1]
            val minX = dataArray[minIdx]
            val minY = dataArray[minIdx + 1]
            val targetRadius = width * 0.015f
            val crosshairLen = targetRadius * 1.5f
            val whiteOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 6f
            }
            val markerTextPaint = Paint(textPaint).apply { this.textSize = width * 0.018f }

            fun drawTarget(x: Float, y: Float, label: String, coreColor: Int) {
                canvas.drawCircle(x, y, targetRadius, whiteOutline)
                canvas.drawLine(x - crosshairLen, y, x + crosshairLen, y, whiteOutline)
                canvas.drawLine(x, y - crosshairLen, x, y + crosshairLen, whiteOutline)
                val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = coreColor
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
                canvas.drawCircle(x, y, targetRadius, corePaint)
                canvas.drawLine(x - crosshairLen, y, x + crosshairLen, y, corePaint)
                canvas.drawLine(x, y - crosshairLen, x, y + crosshairLen, corePaint)
                canvas.drawText(label, x + targetRadius + 5f, y - targetRadius - 5f, markerTextPaint)
            }
            drawTarget(maxX, maxY, "MAX", Color.RED)
            if (drawMinMarker) {
                drawTarget(minX, minY, "MIN", Color.BLUE)
            }
        }
    }
}
