package com.sempermechanics.semper.report

import android.graphics.Bitmap
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.frameParams
import com.sempermechanics.semper.data.session.imageSize
import com.sempermechanics.semper.data.session.roi
import com.sempermechanics.semper.field.FrameParams
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi

/**
 * The per-session half of a report's inputs: what every frame's
 * [ReportBuilder.ReportBuildParams] shares, plus the per-frame settings a
 * sweep varies. [forFrame] adds the frame's own data and images.
 *
 * Two places assembled the same params by hand: the cloud bundle
 * ([forRecord], from a [SessionRecord]) and the viewer's report
 * (`ViewerReportFactory`, from its `Source`). They differ only in the values
 * they feed in, which is what the two factories capture.
 *
 * Its inputs are the app's own value types ([FrameParams], [Roi],
 * [ImageSize]) rather than copies of their fields: a frame's parameters are
 * [FrameParams.at]'s, so a frame past the end of a sweep's per-frame list
 * uses the session value, as both callers did.
 */
data class ReportSource(
    val sessionId: String,
    /** The reference as the user named it; the report derives the specimen from it. */
    val refName: String,
    /** The names a frame's report prints, looked up by [forFrame]'s `nameIndex`. */
    val frameNames: List<String>,
    /** The reference's size, in pixels. */
    val size: ImageSize,
    /** The ROI the run solved over. */
    val roi: Roi,
    val strainMethod: String,
    /** Every frame's solver parameters; a sweep varies them frame by frame. */
    val frameParams: FrameParams,
    val engineStats: EngineStats = EngineStats.EMPTY,
    /** The viewer's PDFs draw the MIN marker too; the cloud bundle's draw MAX only. */
    val drawMinMarker: Boolean = true,
) {
    /**
     * Frame [frameIndex]'s build params. [nameIndex] picks its printed name
     * from [frameNames]: the viewer passes the planned frame (they differ past
     * a skipped frame), a sweep and the cloud bundle the frame itself.
     * [analysisDate] defaults to now, as both callers stamp it.
     * [onFramePhoto] says [defImgForCover] is the frame's own photo, not the
     * reference standing in for it: the field images are then drawn on it, at
     * the displaced positions, as the viewer shows that frame (TD-139).
     */
    @Suppress("LongParameterList") // the frame's own inputs; the rest is this source
    fun forFrame(
        frameIndex: Int,
        data: FloatArray,
        baseImg: Bitmap,
        defImgForCover: Bitmap,
        nameIndex: Int = frameIndex,
        analysisDate: String = ReportBuilder.currentAnalysisDate(),
        onFramePhoto: Boolean = false,
    ): ReportBuilder.ReportBuildParams {
        val params = frameParams.at(frameIndex)
        return ReportBuilder.ReportBuildParams(
            data = data,
            baseImg = baseImg,
            defImgForCover = defImgForCover,
            imgW = size.width,
            imgH = size.height,
            step = params.step,
            sessionId = sessionId,
            specimenName = ReportImageNames.specimen(refName),
            analysisDate = analysisDate,
            subsetSize = params.subset,
            strainWindow = params.strainWindow,
            strainMethod = strainMethod,
            roi = roi,
            engineStats = engineStats,
            referenceImageName = ReportImageNames.reference(refName),
            deformedImageName = ReportImageNames.deformed(frameNames, nameIndex),
            drawMinMarker = drawMinMarker,
            fieldsOnFramePhoto = onFramePhoto,
        )
    }

    companion object {
        /** The viewer's session id when the analysis has none (never synced, or run offline). */
        const val OFFLINE_SESSION_ID = "Local_Offline_Mode"

        /** The strain method a record that stored none was run with. */
        const val DEFAULT_STRAIN_METHOD = "VSG"

        /** The cloud bundle's source (`SessionUploadBundler.renderFrame`). */
        fun forRecord(record: SessionRecord): ReportSource = ReportSource(
            sessionId = record.id,
            refName = record.refName,
            frameNames = bundleFrameNames(record),
            size = record.imageSize,
            roi = record.roi,
            strainMethod = record.strainMethod.ifBlank { DEFAULT_STRAIN_METHOD },
            frameParams = record.frameParams,
            engineStats = EngineStats.fromList(record.engineStats),
            drawMinMarker = false,
        )

        /**
         * The names the cloud bundle's PDFs print: a sweep frame with no label
         * gets the `Combination_N` its folder and `metadata.json` use
         * ([ReportImageNames.sweepLabel]), where [SessionRecord.frameNames]
         * would leave it to print `Frame_N`. The viewer keeps the record's own
         * list: its captions and exports still say `Frame N` for such a frame.
         */
        private fun bundleFrameNames(record: SessionRecord): List<String> = if (record.isSweep) {
            List(maxOf(record.defNames.size, record.sweepLabels.size)) {
                ReportImageNames.sweepLabel(record.sweepLabels, it)
            }
        } else {
            record.defNames
        }
    }
}
