// Share sheet UI: one row per export kind, each started through [runJob]; the
// generators live in ShareExportBuilder.

package com.sempermechanics.semper.ui.viewer.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.mechanical.LabSession
import com.sempermechanics.semper.data.mechanical.loadOfFrame
import com.sempermechanics.semper.data.policy.AppPolicy
import com.sempermechanics.semper.data.policy.FeatureAccess
import com.sempermechanics.semper.data.policy.FeatureLocks
import com.sempermechanics.semper.databinding.SheetShareBinding
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.FrameParams
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.ValueRange
import com.sempermechanics.semper.report.ReportImageNames
import com.sempermechanics.semper.report.StressStrain
import com.sempermechanics.semper.ui.common.dialog.CrispToast
import com.sempermechanics.semper.ui.common.dialog.inflateSheet
import com.sempermechanics.semper.ui.viewer.mechanical.LabReportExporter
import com.sempermechanics.semper.ui.viewer.summary.SummaryAnimation
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Results share sheet (wireframe 08). One scope rule: photos share the
 * current frame; the PDF and CSV cover the whole analysis; the ZIP bundles
 * everything. Fast single-photo export still generates into `cacheDir/share`
 * then offers Save/Share. Slow exports (PDF, ZIP, all-fields, field GIFs, CSV)
 * pick a Save-to-Files destination first, then write there. Field GIFs are
 * single-setting only; a parameter sweep hides that row.
 */
internal class ShareCenter(private val activity: AppCompatActivity, private val source: ShareSource) {

    private val snap by lazy { source.buildShareSnapshot() }

    private fun ensureShareAllowed(): Boolean {
        val blocked = blockedMessage(access(activity)) ?: return true
        CrispToast.show(activity, activity.getString(blocked), long = true)
        return false
    }

    fun show() {
        if (!ensureShareAllowed()) return
        val s = snap ?: return
        val sheet = inflateSheet(activity, R.layout.sheet_share)
        val v = SheetShareBinding.bind(sheet.view)

        val frameName = s.nameAt(s.frameIndex) ?: "Frame ${s.plannedAt(s.frameIndex) + 1}"
        ShareSheetCopy(
            position = s.frameIndex,
            frames = s.batchFiles.size,
            isSweep = s.isSweep,
            typeString = s.typeString,
            frameName = frameName,
            sourceName = s.sourceImageName(s.frameIndex) ?: frameName,
        ).applyTo(v, activity.resources)

        sheet.row(R.id.rowSharePhoto) { runJob(ShareKind.PHOTO) }
        sheet.row(R.id.rowShareAllPhotos) { offerSlowExport(ShareKind.PHOTOS, s) }
        // ShareSheetCopy hides the Animations row on a sweep; only a series wires it.
        if (!s.isSweep) sheet.row(R.id.rowShareAnimations) { offerSlowExport(ShareKind.GIFS, s) }
        // A typed test with loads offers the student's lab report.
        val labOffered = LabReportExporter.offered(
            s.lab.testType,
            hasLoads = s.loadsN.isNotEmpty(),
            isSweep = s.isSweep,
            hasLoadPoint = s.lab.geometry.loadPoint.isSet,
        )
        if (labOffered) {
            v.rowShareLabReport.visibility = View.VISIBLE
            v.rowShareLabReport.contentDescription = activity.getString(R.string.share_lab_report_desc)
            sheet.row(R.id.rowShareLabReport) { offerSlowExport(ShareKind.LAB_PDF, s) }
        }
        sheet.row(R.id.rowSharePdf) { offerSlowExport(ShareKind.PDF, s) }
        sheet.row(R.id.rowShareCsv) { offerSlowExport(ShareKind.CSV, s) }
        sheet.row(R.id.rowShareZip) { offerSlowExport(ShareKind.ZIP, s) }
        sheet.show()
    }

    // ── Job runner: ShareExportJobs (survives rotation) → share sheet / file ──

    /**
     * Save vs Share before any generation. Save opens SAF immediately; Share
     * still has to wait on the job, then hands the file to the system sheet.
     */
    private fun offerSlowExport(kind: ShareKind, s: Snapshot) {
        SendToSheet.showChooser(
            activity,
            onSave = { source.pickShareDocument(kind, suggestedName(kind, s)) },
            onShare = { runJob(kind, direct = true) },
        )
    }

    /**
     * Writes an export of the kind saved as [kind] (a [ShareKind.wire]) into
     * the picked document [uri]. A kind this build does not know fails the
     * export as any failed export does.
     */
    internal fun writeKindToUri(kind: String, uri: Uri) {
        val known = ShareKind.fromWire(kind)
        if (known == null) {
            Timber.e("Unknown share kind %s", kind)
            CrispToast.show(activity, activity.getString(R.string.share_failed), long = true)
            return
        }
        runJob(known, destUri = uri)
    }

    /** The name the save-as picker suggests for an export of [kind] from [s]. */
    private fun suggestedName(kind: ShareKind, s: Snapshot): String = when (kind) {
        ShareKind.PDF -> "${s.baseName}_report.pdf"
        ShareKind.LAB_PDF -> "${s.baseName}_lab_report.pdf"
        ShareKind.CSV -> "${s.baseName}_data.csv"
        ShareKind.PHOTOS -> "${s.baseName}_fields_frame${s.frameIndex + 1}.zip"
        ShareKind.GIFS -> "${s.baseName}_animations.zip"
        ShareKind.PHOTO, ShareKind.ZIP ->
            "${s.baseName}_everything_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip"
    }

    /**
     * Starts an export of [kind] in the viewer's [ShareExportJobs], which outlive a
     * rotation; [ShareExportController] shows its progress and delivers the result.
     *
     * The job is given data only — the snapshot taken here on the main thread,
     * the application's resources and the cache dir — so it never holds this
     * viewer ([ShareExportBuilder]).
     */
    private fun runJob(kind: ShareKind, destUri: Uri? = null, direct: Boolean = false) {
        val s = snap
        if (s == null) {
            Timber.e("Share snapshot unavailable for %s", kind.wire)
            CrispToast.show(activity, activity.getString(R.string.share_failed), long = true)
            return
        }
        val app = activity.applicationContext
        val resources = app.resources
        source.startExport(kind, activity.getString(kind.progressText), destUri, direct) { report ->
            // The export runs off Main, and so does cacheDir's disk check (readiness TE1-103).
            ShareExportBuilder(s, resources, ShareExportBuilder.newJobDir(app.cacheDir), app).produce(kind, report)
        }
    }

    /**
     * Everything the generators need, captured once from the viewer: the frame
     * and field on screen, and the [reportSource] the rest of the analysis is
     * read from.
     */
    data class Snapshot(
        /**
         * The field of the frame on screen, or null while it is still loading —
         * a save-as answer can reach a recreated viewer first. Only the photo
         * kinds need it; read it through [frameData].
         */
        val data: FloatArray?,
        val batchFiles: List<File>,
        /** Filename-safe base for exports, e.g. the specimen/reference name. */
        val baseName: String,
        val frameIndex: Int,
        val dataIndex: Int,
        val typeString: String,
        /** Single-setting overview builder; null on a parameter sweep. */
        val summary: SummaryAnimation?,
        /**
         * Each field's GIF colour bounds as the viewer knew them when the job
         * started (a fixed scale, else the sequence range); a field the range
         * pass had not reached yet is absent.
         */
        val summaryBounds: Map<Int, ValueRange>,
        /**
         * What a frame's report page needs from the viewer. Each frame of an
         * all-frames report builds its own cover — its parameters and its
         * deformed image — from this, rather than reusing the one on screen.
         */
        val reportSource: ViewerReportFactory.Source,
        /**
         * Mechanical test, as the viewer has it: bending's dimensions with a
         * deflection correction set in Results, tensile's scale and bias typed
         * there, else the session's. [LabSession.NONE] on a plain DIC session.
         */
        val lab: LabSession = LabSession.NONE,
        /** Machine load per planned frame, N; empty without a load log. */
        val loadsN: FloatArray = FloatArray(0),
        /** The viewer's already-built curve, if a Results surface has been shown. */
        val stressStrain: StressStrain.Curve? = null,
    ) {
        /** How this session's loads become stress. */
        val stressModel: StressStrain.Model get() = lab.stressModelOrAxial()

        /**
         * The machine load behind the frame at position [index], or null without one.
         * Loads are per planned frame, so past a skipped frame this is not
         * `loadsN[index]` (TD-110).
         */
        fun loadAt(index: Int): Float? = loadsN.loadOfFrame(plannedAt(index))

        val imageSize: ImageSize get() = reportSource.imageSize

        /** Every frame's solver parameters; a sweep's vary frame by frame. */
        val frameParams: FrameParams get() = reportSource.frameParams

        /** True when the frames are a parameter sweep's combinations, not a time series. */
        val isSweep: Boolean get() = reportSource.isSweep

        /** Display-scale reference (null while its decode is in flight); exports prefer [refImagePath]. */
        val baseImage: Bitmap? get() = reportSource.displayBase

        val refImagePath: String? get() = reportSource.args.refPath.ifBlank { null }

        val defImagePaths: List<String> get() = reportSource.defImagePaths

        /** Grid pitch of frame [index] — what rendering that frame depends on. */
        fun stepAt(index: Int): Int = frameParams.at(index).step

        /** The planned frame behind the frame at position [index]. */
        fun plannedAt(index: Int): Int = reportSource.plannedAt(index)

        /** The name of the frame at position [index], or null when it has none. */
        fun nameAt(index: Int): String? = ReportImageNames.frameName(reportSource.frameNames, plannedAt(index))

        /** The field of frame [frameIndex]: the viewer's copy, else read from disk. Off the main thread. */
        fun frameData(): FloatArray = data
            ?: batchFiles.getOrNull(frameIndex)?.let { DicResult.decodeDatFile(it) }
            ?: error("Frame ${frameIndex + 1} is unreadable")

        /** Filename stamped on share photos: the real image, not a sweep settings label. */
        fun sourceImageName(frameIndex: Int): String? =
            if (isSweep) defImagePaths.firstOrNull()?.let { File(it).name } else nameAt(frameIndex)
    }

    companion object {
        /**
         * How Share shows for this account now ([FeatureLocks.share]): greyed
         * whenever it is not [FeatureAccess.OPEN], never hidden.
         */
        fun access(context: Context): FeatureAccess =
            FeatureLocks.share(LicenseEntitlements.shareLicensed(context), AppPolicy.shareAllowed(context))

        /**
         * What a tap on a greyed Share says, or null when it works: the
         * licence note when the licence lacks sharing, "temporarily
         * unavailable" when the share kill switch has it off.
         */
        @StringRes
        fun blockedMessage(access: FeatureAccess): Int? = when (access) {
            FeatureAccess.OPEN -> null
            FeatureAccess.LOCKED -> R.string.license_lock_note
            FeatureAccess.PAUSED, FeatureAccess.HIDDEN -> R.string.share_paused
        }
    }
}

/**
 * What the share sheet needs from the viewer: the snapshot it exports from,
 * the save-as picker, and the job runner that outlives a rotation.
 */
internal interface ShareSource {
    /** Everything an export needs, or null before the frame set is read or with no frames. */
    fun buildShareSnapshot(): ShareCenter.Snapshot?

    /** Opens the save-as picker for a slow export of [kind], suggesting [filename]. */
    fun pickShareDocument(kind: ShareKind, filename: String)

    /** Starts an export of [kind]; see [ShareExportController.start]. */
    fun startExport(
        kind: ShareKind,
        title: String,
        destUri: Uri?,
        direct: Boolean,
        produce: suspend (report: ExportReport) -> Pair<File, String>,
    )
}
