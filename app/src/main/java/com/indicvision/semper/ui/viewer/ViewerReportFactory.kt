// Report assembly maps many result fields and engine-stat indices into the
// report model; the literal indices/constants read clearest inline.
@file:Suppress("CyclomaticComplexMethod", "MagicNumber")

package com.indicvision.semper.ui.viewer

import android.graphics.Bitmap
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.ReportData
import com.indicvision.semper.report.ReportImageNames
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
        val refPath = host.args.refPath.ifBlank { null }
        val decodedCapped = refPath?.let {
            BitmapDecode.decodeFileForView(
                it,
                capW,
                capH,
                cap,
                rawWidth = host.imgW,
                rawHeight = host.imgH,
            )
        }
        // Without a reference file, the viewer's display-size reference stands in
        // as it is: buildReport scales whatever it is given into its capped
        // composite and keeps only 600 px copies, so scaling it up first (it was
        // scaled to the full sensor size, ~104 MB at 26 MP) bought nothing.
        val baseImg = decodedCapped ?: host.cachedBaseImage ?: return null
        val ownsBase = baseImg === decodedCapped

        val frameStep = host.sweepSteps?.getOrNull(frameIndex) ?: host.baseStep
        val frameSubset = host.sweepSubsets?.getOrNull(frameIndex)
            ?: host.args.subsetSize
        val frameStrainWin = host.sweepStrainWins?.getOrNull(frameIndex)
            ?: host.args.strainWindow

        val statsArray = host.args.engineStatsArray() ?: FloatArray(16)
        val engineStats = if (statsArray.size >= 16) {
            EngineStats.fromArray(statsArray)
        } else {
            EngineStats(0, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        // buildReport keeps only a downscaled copy of the cover images, so the
        // decodes here are ours to free — on a throw too, since an all-frames
        // report calls this once per frame.
        var realDefImg: Bitmap? = null
        try {
            // This frame's own image. It used to be the viewer's opening one for
            // every page — the first frame after a run, the reference from Home —
            // under each frame's own "Def:" name.
            val defImg = host.deformedImagePathAt(frameIndex)?.let {
                BitmapDecode.decodeFileForView(
                    it,
                    capW,
                    capH,
                    cap,
                    rawWidth = host.imgW,
                    rawHeight = host.imgH,
                )
            } ?: baseImg
            realDefImg = defImg

            return buildReportWith(
                host = host,
                data = data,
                baseImg = baseImg,
                realDefImg = defImg,
                frameIndex = frameIndex,
                frameStep = frameStep,
                frameSubset = frameSubset,
                frameStrainWin = frameStrainWin,
                engineStats = engineStats,
            )
        } finally {
            realDefImg?.takeIf { it !== baseImg }?.recycle()
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
                sessionId = host.args.sessionId ?: "Local_Offline_Mode",
                specimenName = ReportImageNames.specimen(host.args.refName),
                analysisDate = ReportBuilder.currentAnalysisDate(),
                subsetSize = frameSubset,
                strainWindow = frameStrainWin,
                strainMethod = host.args.strainMethod,
                roiData = RoiData(host.roiX, host.roiY, host.roiW, host.roiH),
                engineStats = engineStats,
                referenceImageName = ReportImageNames.reference(host.args.refName),
                // Named from the same planned frame as the cover image, so the
                // two agree past a frame the batch skipped. A sweep's names are
                // its combination labels, one per node.
                deformedImageName = ReportImageNames.deformed(
                    host.originalDefNames,
                    if (host.isSweep) frameIndex else host.plannedFrameIndex(frameIndex),
                ),
            ),
        )
    }
}
