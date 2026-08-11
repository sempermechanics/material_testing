// Report assembly maps many result fields and engine-stat indices into the
// report model; the literal indices/constants read clearest inline.
@file:Suppress("CyclomaticComplexMethod", "MagicNumber")

package com.indicvision.semper.ui.viewer

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.indicvision.semper.DicKeys
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.ReportData
import com.indicvision.semper.report.RoiData
import com.indicvision.semper.report.VisualizationEngine

/**
 * Builds [ReportData] for the current (or a given) frame. Frame-varying fields
 * — step, subset, strain window, deformed name — are read by index so an
 * all-frames report describes each frame correctly.
 */
object ViewerReportFactory {

    fun buildReportData(
        host: ResultViewerActivity,
        frameIndex: Int,
        data: FloatArray,
    ): ReportData? {
        // buildReport downscales every cover to 600 px, so a full-resolution decode of
        // a 26 MP reference (~104 MB, and once per frame in an all-frames report) is
        // pure waste. Decode no larger than REPORT_MAX_EDGE — the report's own render
        // cap — via inSampleSize, so peak stays a few MB.
        val cap = VisualizationEngine.REPORT_MAX_EDGE
        val (capW, capH) = VisualizationEngine.cappedDims(host.imgW, host.imgH, cap)
        val refPath = host.intent.getStringExtra(DicKeys.REF_PATH)
        val decodedCapped = refPath?.let { BitmapDecode.decodeFileForView(it, capW, capH, cap) }
        val cached = host.cachedBaseImage
        val baseImg = when {
            decodedCapped != null -> decodedCapped
            cached != null && cached.width == host.imgW && cached.height == host.imgH -> cached
            cached != null -> cached.scale(host.imgW, host.imgH)
            else -> return null
        }
        val ownsBase = baseImg !== cached

        val frameStep = host.sweepSteps?.getOrNull(frameIndex) ?: host.baseStep
        val frameSubset = host.sweepSubsets?.getOrNull(frameIndex)
            ?: host.intent.getIntExtra(DicKeys.SUBSET_SIZE, 41)
        val frameStrainWin = host.sweepStrainWins?.getOrNull(frameIndex)
            ?: host.intent.getIntExtra(DicKeys.STRAIN_WINDOW, 15)

        val statsArray = host.intent.getFloatArrayExtra(DicKeys.ENGINE_STATS) ?: FloatArray(16)
        val engineStats = if (statsArray.size >= 16) {
            EngineStats.fromArray(statsArray)
        } else {
            EngineStats(0, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        val realDefImg = host.currentDefPath?.let { BitmapDecode.decodeFileForView(it, capW, capH, cap) } ?: baseImg

        // buildReport keeps only a downscaled copy of the cover images, so the
        // full-size decode above is ours to free — and an all-frames report
        // calls this once per frame.
        return buildReportWith(
            host = host,
            data = data,
            baseImg = baseImg,
            realDefImg = realDefImg,
            frameIndex = frameIndex,
            frameStep = frameStep,
            frameSubset = frameSubset,
            frameStrainWin = frameStrainWin,
            engineStats = engineStats,
        ).also {
            if (realDefImg !== baseImg) realDefImg.recycle()
            if (ownsBase) baseImg.recycle()
        }
    }

    @Suppress("LongParameterList") // one call site; all of it is per-frame state
    fun buildReportWith(
        host: ResultViewerActivity,
        data: FloatArray,
        baseImg: Bitmap,
        realDefImg: Bitmap,
        frameIndex: Int,
        frameStep: Int,
        frameSubset: Int,
        frameStrainWin: Int,
        engineStats: EngineStats,
    ): ReportData {
        return ReportBuilder.buildReport(
            ReportBuilder.ReportBuildParams(
                data = data,
                baseImg = baseImg,
                defImgForCover = realDefImg,
                imgW = host.imgW,
                imgH = host.imgH,
                step = frameStep,
                sessionId = host.intent.getStringExtra(DicKeys.SESSION_ID) ?: "Local_Offline_Mode",
                specimenName = host.intent.getStringExtra(DicKeys.REF_NAME)?.substringBeforeLast(".")
                    ?: "Batch Analysis",
                analysisDate = ReportBuilder.currentAnalysisDate(),
                subsetSize = frameSubset,
                strainWindow = frameStrainWin,
                strainMethod = host.intent.getStringExtra(DicKeys.STRAIN_METHOD) ?: "VSG",
                roiData = RoiData(host.roiX, host.roiY, host.roiW, host.roiH),
                engineStats = engineStats,
                referenceImageName = host.intent.getStringExtra(DicKeys.REF_NAME) ?: "reference.png",
                deformedImageName = host.originalDefNames.getOrNull(frameIndex)
                    ?: "Frame_${frameIndex + 1}",
            ),
        )
    }
}
