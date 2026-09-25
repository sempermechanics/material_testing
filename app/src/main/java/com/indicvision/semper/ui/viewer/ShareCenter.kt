// Share/export hub: one method per export target (PNG, CSV, PDF, bundle) with
// early-return guards and broad IO catches around file writes; literal quality
// constants read clearest inline, so these rules are suppressed for this file.
@file:Suppress("MagicNumber", "ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")

@file:SuppressLint("InflateParams")

package com.indicvision.semper.ui.viewer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.data.CacheJanitor
import com.indicvision.semper.data.LicenseEntitlements
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.imaging.ImageEncode
import com.indicvision.semper.report.AnalysisCsvWriter
import com.indicvision.semper.report.PdfReportGenerator
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.ui.common.CrispToast
import com.indicvision.semper.ui.common.DeterminateProgressDialog
import com.indicvision.semper.ui.common.TransferBannerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The Results share sheet (wireframe 08). One scope rule: photos share the
 * current frame; the PDF and CSV cover the whole analysis; the ZIP bundles
 * everything. Fast single-photo export still generates into `cacheDir/share`
 * then offers Save/Share. Slow exports (PDF, ZIP, all-fields, field GIFs, CSV)
 * pick a Save-to-Files destination first, then write there. Field GIFs are
 * single-setting only; a parameter sweep hides that row.
 */
class ShareCenter(private val host: ResultViewerActivity) {

    private val snap by lazy { host.buildShareSnapshot() }

    /**
     * The share snapshot, which [show] has already confirmed is non-null before
     * opening the sheet. Every generator below runs inside [runJob]'s try/catch, so
     * throwing here (rather than a raw `!!` NPE) turns the impossible-but-defended
     * "no snapshot" case into the normal "share failed" snackbar instead of a crash.
     */
    private fun requireSnapshot(): Snapshot =
        snap ?: error("Share snapshot unavailable")

    private fun ensureShareAllowed(): Boolean {
        if (LicenseEntitlements.shareEnabled(host)) return true
        CrispToast.show(host, host.getString(R.string.share_licensed_only), long = true)
        return false
    }

    fun show() {
        if (!ensureShareAllowed()) return
        val s = snap ?: return
        val sheet = BottomSheetDialog(host)
        val v = host.layoutInflater.inflate(R.layout.sheet_share, null)
        sheet.setContentView(v)

        val frameName = s.defNames.getOrNull(s.frameIndex) ?: "Frame ${s.frameIndex + 1}"
        v.findViewById<TextView>(R.id.tvShareCaption).text =
            host.resources.getQuantityString(
                R.plurals.share_caption_fmt,
                s.batchFiles.size,
                s.frameIndex + 1,
                s.batchFiles.size,
            )
        v.findViewById<TextView>(R.id.tvSharePhotoSub).text =
            host.getString(R.string.share_photo_sub_fmt, s.typeString, frameName)
        val allName = sourceImageName(s, s.frameIndex) ?: frameName
        v.findViewById<TextView>(R.id.tvShareAllPhotosSub).text =
            host.resources.getQuantityString(
                R.plurals.share_all_photos_sub_fmt,
                FIELDS.size,
                FIELDS.size,
                allName,
            )
        v.findViewById<TextView>(R.id.tvSharePdfSub).text =
            host.resources.getQuantityString(R.plurals.share_pdf_sub_fmt, s.batchFiles.size, s.batchFiles.size)
        v.findViewById<TextView>(R.id.tvShareCsvSub).text =
            host.resources.getQuantityString(R.plurals.share_csv_sub_fmt, s.batchFiles.size, s.batchFiles.size)

        v.findViewById<View>(R.id.rowSharePhoto).setOnClickListener {
            sheet.dismiss()
            runJob(R.string.share_generating) { listOf(currentPhoto()) to "image/png" }
        }
        v.findViewById<View>(R.id.rowShareAllPhotos).setOnClickListener {
            sheet.dismiss()
            offerSlowExport(KIND_PHOTOS, "application/zip", photosZipName(), R.string.share_generating)
        }
        // Parameter sweeps are not a time series — no summary GIF and no Animations row.
        val animationsRow = v.findViewById<View>(R.id.rowShareAnimations)
        if (s.stepPerFrame != null) {
            animationsRow.visibility = View.GONE
        } else {
            animationsRow.setOnClickListener {
                sheet.dismiss()
                offerSlowExport(KIND_GIFS, "application/zip", animationsZipName(), R.string.share_generating_gif)
            }
        }
        v.findViewById<View>(R.id.rowSharePdf).setOnClickListener {
            sheet.dismiss()
            offerSlowExport(KIND_PDF, "application/pdf", pdfName(), R.string.share_generating_pdf)
        }
        v.findViewById<View>(R.id.rowShareCsv).setOnClickListener {
            sheet.dismiss()
            offerSlowExport(KIND_CSV, "text/csv", csvName(), R.string.share_generating)
        }
        v.findViewById<View>(R.id.rowShareZip).setOnClickListener {
            sheet.dismiss()
            offerSlowExport(KIND_ZIP, "application/zip", zipName(), R.string.share_generating_pdf)
        }
        sheet.show()
    }

    // ── Job runner: progress dialog → system share sheet (+ Local) ───────

    /**
     * Save vs Share before any generation. Save opens SAF immediately; Share
     * still has to wait on the job, then hands the file to the system sheet.
     */
    private fun offerSlowExport(
        kind: String,
        mime: String,
        filename: String,
        progressText: Int,
    ) {
        SendToSheet.showChooser(
            host,
            onSave = { host.pickShareDocument(kind, mime, filename) },
            onShare = {
                runJob(progressText, shareDirect = true) { report -> buildKind(kind, report) }
            },
        )
    }

    internal fun writeKindToUri(kind: String, uri: Uri) {
        val progressText = when (kind) {
            KIND_PDF, KIND_ZIP -> R.string.share_generating_pdf
            KIND_GIFS -> R.string.share_generating_gif
            else -> R.string.share_generating
        }
        runJob(progressText, destUri = uri) { report -> buildKind(kind, report) }
    }

    private suspend fun buildKind(
        kind: String,
        report: (Int, String) -> Unit,
    ): Pair<List<File>, String> = when (kind) {
        KIND_PDF -> listOf(allFramesPdf(report)) to "application/pdf"
        KIND_ZIP -> listOf(everythingZip(report)) to "application/zip"
        KIND_CSV -> listOf(batchCsv()) to "text/csv"
        KIND_PHOTOS -> allFieldPhotos() to "image/png"
        KIND_GIFS -> {
            check(requireSnapshot().stepPerFrame == null) { "Animations are not offered for sweeps" }
            fieldAnimations() to "image/gif"
        }
        else -> error("Unknown share kind $kind")
    }

    private fun pdfName(): String = "${requireSnapshot().baseName}_report.pdf"

    private fun csvName(): String = "${requireSnapshot().baseName}_data.csv"

    private fun zipName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${requireSnapshot().baseName}_everything_$ts.zip"
    }

    private fun photosZipName(): String {
        val s = requireSnapshot()
        return "${s.baseName}_fields_frame${s.frameIndex + 1}.zip"
    }

    private fun animationsZipName(): String = "${requireSnapshot().baseName}_animations.zip"

    private fun runJob(
        progressText: Int,
        destUri: Uri? = null,
        shareDirect: Boolean = false,
        build: suspend (report: (Int, String) -> Unit) -> Pair<List<File>, String>,
    ) {
        var job: Job? = null
        val transferId = "share-$progressText"
        val title = host.getString(progressText)
        fun stopJob() {
            job?.cancel()
            host.shareBanner.remove(transferId)
        }
        val progress = DeterminateProgressDialog(
            host,
            title,
            onCancel = { stopJob() },
            onBackground = {
                host.shareBanner.upsert(
                    TransferBannerController.Transfer(
                        id = transferId,
                        title = title,
                        onCancel = { stopJob() },
                    ),
                )
            },
        )
        progress.show()
        job = host.lifecycleScope.launch {
            try {
                val report: (Int, String) -> Unit = { pct, label ->
                    progress.update(pct, label)
                    if (host.shareBanner.contains(transferId)) {
                        host.shareBanner.updateProgress(transferId, pct, label)
                    }
                }
                val (files, mime) = withContext(Dispatchers.Default) { build(report) }
                // Safety: never hand an empty or missing file to the share sheet —
                // a generator that silently produced nothing would otherwise share
                // a 0-byte document.
                if (files.isEmpty() || files.any { !it.exists() || it.length() == 0L }) {
                    fail(progress, transferId, null, "Share produced no usable files")
                    return@launch
                }
                // SAF saves one document; bundle multi-file exports into a zip first.
                val handoff = withContext(Dispatchers.Default) {
                    if (files.size == 1) {
                        files[0] to mime
                    } else {
                        zipInto(files, "${snap?.baseName ?: "analysis"}_export.zip") to "application/zip"
                    }
                }
                if (!handoff.first.exists() || handoff.first.length() == 0L) {
                    fail(progress, transferId, null, "Bundled export was empty")
                    return@launch
                }
                progress.dismiss()
                host.shareBanner.remove(transferId)
                deliverHandoff(handoff.first, handoff.second, destUri, shareDirect)
            } catch (e: CancellationException) {
                progress.dismiss()
                host.shareBanner.remove(transferId)
                CrispToast.show(host, host.getString(R.string.share_cancelled))
                throw e
            } catch (e: Throwable) {
                // Throwable, not just Exception: a large multi-frame ZIP/PDF export
                // can hit OutOfMemoryError (an Error), which we'd rather surface as
                // a snackbar than let crash the app.
                fail(progress, transferId, e, "Share generation failed")
            }
        }
    }

    private fun fail(
        progress: DeterminateProgressDialog,
        transferId: String,
        e: Throwable?,
        log: String,
    ) {
        progress.dismiss()
        host.shareBanner.remove(transferId)
        if (e != null) Timber.e(e, log) else Timber.e(log)
        CrispToast.show(host, host.getString(R.string.share_failed), long = true)
    }

    private suspend fun deliverHandoff(
        file: File,
        mime: String,
        destUri: Uri?,
        shareDirect: Boolean,
    ) {
        if (destUri != null) {
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    host.contentResolver.openOutputStream(destUri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: 0L
                }.onFailure { Timber.e(it, "Save to Files failed") }.getOrDefault(0L)
            }
            Toast.makeText(
                host,
                if (copied > 0L) R.string.save_success else R.string.save_failed,
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (shareDirect) {
            host.startActivity(SendToSheet.shareChooser(host, file, mime))
        } else {
            shareWithLocalOption(file, mime)
        }
    }

    /**
     * Our own "Send to" sheet: Save to Files (folder icon) + Share. Owning the
     * rows is the only reliable way to show a folder icon — the system share
     * sheet ignores custom icons on EXTRA_INITIAL_INTENTS on Android 12+.
     */
    private fun shareWithLocalOption(file: File, mime: String) {
        SendToSheet.show(host, file, mime)
    }

    private fun shareDir(): File = CacheJanitor.shareDir(host.cacheDir)

    /** Bundle several files into a single zip — the SAF picker saves one document. */
    private fun zipInto(files: List<File>, zipName: String): File {
        val out = File(shareDir(), zipName)
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    // ── Generators ───────────────────────────────────────────────────────

    /**
     * Annotated PNG of one field for one frame's data. Composited at the
     * [VisualizationEngine.REPORT_MAX_EDGE]-capped size rather than full sensor
     * resolution: a 26 MP reference otherwise held four ~100 MB ARGB bitmaps at once
     * (heatmap + base + out + decode) per field. Marker coordinates are scaled by the
     * same factor, mirroring [ReportBuilder.buildReport].
     */
    private fun renderAnnotated(
        data: FloatArray,
        dataIndex: Int,
        typeString: String,
        frameIndex: Int,
        baseCache: MutableMap<Pair<Int, Int>, Bitmap>? = null,
    ): Bitmap {
        // Optional cache: multi-field export reuses one decoded reference bitmap.
        val s = requireSnapshot()
        val renderScale = VisualizationEngine.cappedRenderScale(s.imgW, s.imgH, VisualizationEngine.REPORT_MAX_EDGE)
        val renderW = (s.imgW * renderScale).toInt().coerceAtLeast(1)
        val renderH = (s.imgH * renderScale).toInt().coerceAtLeast(1)

        val (heatmap, actualMin, actualMax) = VisualizationEngine.generateHeatmap(
            data,
            s.imgW,
            s.imgH,
            dataIndex,
            s.stepAt(frameIndex),
            null,
            null,
            maxLongEdge = VisualizationEngine.REPORT_MAX_EDGE,
        )
        val base = loadCappedBase(s, renderW, renderH, baseCache)
        val out = createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(base, null, Rect(0, 0, renderW, renderH), Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.drawBitmap(heatmap, 0f, 0f, Paint().apply { alpha = HEATMAP_ALPHA })
        // Signed, as the PDF does: with absolute values "MIN" marked the strain
        // nearest zero under a label giving the most negative.
        val extrema = ReportBuilder.computeFieldExtrema(data, dataIndex, absoluteStrainValues = false)
        val unit = if (DicResult.isStrainFieldIndex(dataIndex)) "mε" else "px"
        ReportBuilder.bakeAnnotationsToCanvas(
            canvas, renderW, renderH, actualMin, actualMax,
            typeString, unit, extrema.maxIdx, extrema.minIdx, data,
            dataIndex = dataIndex,
            coordScale = renderScale,
            imageName = sourceImageName(s, frameIndex),
        )
        heatmap.recycle()
        if (baseCache == null && base !== s.baseImage) base.recycle()
        return out
    }

    /** Filename stamped on share photos: the real image, not a sweep settings label. */
    private fun sourceImageName(s: Snapshot, frameIndex: Int): String? {
        if (s.stepPerFrame != null) {
            return s.defImagePaths.firstOrNull()?.let { File(it).name }
        }
        return s.defNames.getOrNull(frameIndex)?.takeIf { it.isNotBlank() }
    }

    /**
     * Reference image for compositing, decoded no larger than the capped composite it
     * draws into — prefer the on-disk reference (inSampleSize-decoded) over the
     * viewer's display bitmap so export quality doesn't depend on viewer scale.
     */
    private fun loadCappedBase(
        s: Snapshot,
        renderW: Int,
        renderH: Int,
        cache: MutableMap<Pair<Int, Int>, Bitmap>? = null,
    ): Bitmap {
        val key = renderW to renderH
        cache?.get(key)?.let { return it }
        s.refImagePath?.let { path ->
            BitmapDecode.decodeFileForView(
                path,
                renderW,
                renderH,
                VisualizationEngine.REPORT_MAX_EDGE,
                rawWidth = s.imgW,
                rawHeight = s.imgH,
            )?.let { decoded ->
                cache?.put(key, decoded)
                return decoded
            }
        }
        val display = s.baseImage ?: error("No reference image for export")
        val scaled = if (display.width == renderW && display.height == renderH) {
            display
        } else {
            display.scale(renderW, renderH)
        }
        if (scaled !== display) cache?.put(key, scaled)
        return scaled
    }

    private fun recycleBaseCache(cache: MutableMap<Pair<Int, Int>, Bitmap>, s: Snapshot) {
        cache.values.forEach { bmp ->
            if (bmp !== s.baseImage) bmp.recycle()
        }
        cache.clear()
    }

    private fun writePng(bmp: Bitmap, name: String): File {
        val f = File(shareDir(), name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, it) }
        bmp.recycle()
        return f
    }

    private fun currentPhoto(): File {
        val s = requireSnapshot()
        return writePng(
            renderAnnotated(s.data, s.dataIndex, s.typeString, s.frameIndex),
            "${s.baseName}_${s.typeString}_frame${s.frameIndex + 1}.png",
        )
    }

    private fun allFieldPhotos(): List<File> {
        val s = requireSnapshot()
        val baseCache = mutableMapOf<Pair<Int, Int>, Bitmap>()
        return try {
            FIELDS.map { (label, idx) ->
                writePng(
                    renderAnnotated(s.data, idx, label, s.frameIndex, baseCache),
                    "${s.baseName}_${label}_frame${s.frameIndex + 1}.png",
                )
            }
        } finally {
            recycleBaseCache(baseCache, s)
        }
    }

    /**
     * All five fields as looping GIFs, each covering every frame on that field's
     * whole-sequence colour scale.
     *
     * Shared as a set rather than one at a time: the point of the animations is
     * that they are directly comparable, which only holds if you have them all.
     * Fields the viewer has not rendered yet are built here, so sharing works
     * the moment the screen opens.
     */
    private suspend fun fieldAnimations(): List<File> {
        val s = requireSnapshot()
        val animation = s.summary ?: return emptyList()
        return SummaryAnimation.FIELDS.mapNotNull { (label, index) ->
            val bounds = s.summaryBounds(index) ?: return@mapNotNull null
            animation.build(index, label, bounds)
        }
    }

    /**
     * One CSV covering every frame's solved points, via the shared
     * [AnalysisCsvWriter] the cloud upload uses too. A sweep leads each row with
     * its settings columns; an ordinary analysis leads with the image name.
     */
    private fun batchCsv(): File {
        val s = requireSnapshot()
        val sweep = s.stepPerFrame != null
        // A sweep ran every combination against the one image; a batch has one
        // image per frame.
        val sweepImage = s.defImagePaths.firstOrNull()?.let { File(it).name } ?: "image"
        val frames = s.batchFiles.mapIndexed { index, file ->
            AnalysisCsvWriter.Frame(
                image = if (sweep) sweepImage else s.defNames.getOrNull(index) ?: "Frame_${index + 1}",
                subset = s.subsetPerFrame?.getOrNull(index) ?: s.subset,
                step = s.stepPerFrame?.getOrNull(index) ?: s.step,
                strainWindow = s.strainWindowPerFrame?.getOrNull(index) ?: s.strainWindow,
                data = { DicResult.decodeDatFile(file) },
            )
        }
        val metadata = AnalysisCsvWriter.Metadata(
            referenceName = s.referenceName.ifBlank { s.baseName },
            strainMethod = s.strainMethod,
            imgW = s.imgW,
            imgH = s.imgH,
            roiX = s.roiX,
            roiY = s.roiY,
            roiW = s.roiW,
            roiH = s.roiH,
        )
        val f = File(shareDir(), "${s.baseName}_data.csv")
        AnalysisCsvWriter.write(f, sweep, frames, metadata)
        return f
    }

    /**
     * One PDF holding every frame's full report, concatenated: each frame gets
     * the same cover / field-pages structure a single-frame report has, and one
     * telemetry page closes the document.
     */
    private suspend fun allFramesPdf(report: (Int, String) -> Unit = { _, _ -> }): File {
        val s = requireSnapshot()
        val f = File(shareDir(), "${s.baseName}_report.pdf")
        f.outputStream().use { out ->
            PdfReportGenerator.generateBatch(
                frameCount = s.batchFiles.size,
                dataAt = { index -> frameReport(index) },
                outputStream = out,
                frameTitle = { index -> frameTitle(index) },
                resources = host.resources,
            ).collect { progress ->
                when (progress) {
                    // generateBatch reports failures as a Flow event rather than
                    // throwing; surface it so the share job actually fails (and logs)
                    // instead of silently handing back an empty PDF.
                    is PdfReportGenerator.Progress.Error -> throw progress.ex
                    is PdfReportGenerator.Progress.Status -> report(progress.percent, progress.message)
                    PdfReportGenerator.Progress.Complete -> Unit
                }
            }
        }
        return f
    }

    /**
     * The frame's own report data. Built one frame at a time — the generator
     * recycles each frame's bitmaps before asking for the next.
     */
    private fun frameReport(index: Int): com.indicvision.semper.report.ReportData? {
        val s = requireSnapshot()
        val data = DicResult.decodeDatFile(s.batchFiles[index]) ?: return null
        return s.buildReportAt(index, data)
    }

    private fun frameTitle(index: Int): String {
        val s = requireSnapshot()
        val name = s.defNames.getOrNull(index)?.takeIf { it.isNotBlank() }
        return if (name == null) {
            "DIC Analysis Report — Frame ${index + 1}"
        } else {
            "DIC Analysis Report — $name"
        }
    }

    /**
     * The complete-bundle ZIP (`{ts}` = capture time, `yyyyMMdd_HHmmss`):
     * ```
     * ├── {base}_data.csv                      (root)
     * ├── {base}_report.pdf                    (root)
     * └── photos_{ts}/
     *     ├── raw photos/                      reference + deformed originals
     *     ├── animations/                      U..Exy GIFs (single-setting only)
     *     └── results/<NNN_frame>/             U, V, Exx, Eyy, Exy per frame
     * ```
     */
    private suspend fun everythingZip(report: (Int, String) -> Unit = { _, _ -> }): File {
        val s = requireSnapshot()
        // The PDF is the long pole; give it the first 60% of the bar, then the
        // per-frame result images the last 40%.
        val pdf = allFramesPdf { pct, label -> report(pct * 60 / 100, label) }
        val csv = batchCsv()
        val animations = if (s.stepPerFrame != null) emptyList() else fieldAnimations()
        report(62, "Bundling files…")
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(shareDir(), "${s.baseName}_everything_$ts.zip")
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            addRawPhotos(zip, s, ts)
            for (gif in animations) {
                zip.putNextEntry(ZipEntry("photos_$ts/animations/${gif.name}"))
                gif.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            addResultImages(zip, s, ts) { done, total ->
                report(70 + (if (total > 0) done * 30 / total else 0), "Adding result images…")
            }
            // Home of the archive: the data table and the full report.
            zip.putNextEntry(ZipEntry("${s.baseName}_data.csv"))
            csv.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("${s.baseName}_report.pdf"))
            pdf.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        report(100, "Bundling files…")
        return f
    }

    /** `photos_{ts}/raw photos/` — the reference and (best-effort) deformed originals. */
    private fun addRawPhotos(zip: ZipOutputStream, s: Snapshot, ts: String) {
        val dir = "photos_$ts/raw photos"

        val refFile = s.refImagePath?.let { File(it) }?.takeIf { it.exists() }
        if (refFile != null) {
            zip.putNextEntry(ZipEntry("$dir/reference_${refFile.name}"))
            refFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        } else {
            // No persisted reference file (shouldn't happen) — fall back to the
            // in-memory base image so the folder is never empty.
            // No persisted reference path here, so the display base is the only image
            // available — write it as-is (this is the raw-photos folder, not a capped
            // composite).
            s.baseImage?.let { base ->
                zip.putNextEntry(ZipEntry("$dir/reference.png"))
                base.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, zip)
                zip.closeEntry()
            }
        }

        // Deformed originals persisted in the session dir; names already carry a
        // sortable NNNN_ prefix. Guarded so a missing file can't abort the export.
        for (path in s.defImagePaths) {
            val df = File(path)
            if (!df.exists()) continue
            zip.putNextEntry(ZipEntry("$dir/${df.name}"))
            df.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** `photos_{ts}/results/<NNN_frame>/` — every field's annotated heatmap per frame. */
    private fun addResultImages(
        zip: ZipOutputStream,
        s: Snapshot,
        ts: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        for ((index, file) in s.batchFiles.withIndex()) {
            onProgress(index + 1, s.batchFiles.size)
            val data = DicResult.decodeDatFile(file) ?: continue
            val prefix = (index + 1).toString().padStart(3, '0')
            val frameName = s.defNames.getOrNull(index)?.substringBeforeLast('.') ?: "Frame_${index + 1}"
            val folder = "photos_$ts/results/${prefix}_$frameName"
            val baseCache = mutableMapOf<Pair<Int, Int>, Bitmap>()
            try {
                addFrameResultImages(zip, data, index, folder, baseCache)
            } finally {
                recycleBaseCache(baseCache, s)
            }
        }
    }

    private fun addFrameResultImages(
        zip: ZipOutputStream,
        data: FloatArray,
        index: Int,
        folder: String,
        baseCache: MutableMap<Pair<Int, Int>, Bitmap>,
    ) {
        for ((label, idx) in FIELDS) {
            var bmp: Bitmap? = null
            try {
                bmp = renderAnnotated(data, idx, label, index, baseCache)
                zip.putNextEntry(ZipEntry("$folder/$label.png"))
                bmp.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, zip)
                zip.closeEntry()
            } catch (e: Exception) {
                // One unrenderable field shouldn't abort the whole export.
                Timber.w(e, "Skipping %s of frame %d in ZIP export", label, index + 1)
            } finally {
                bmp?.recycle()
            }
        }
    }

    /** Everything the generators need, captured once from the viewer. */
    data class Snapshot(
        val data: FloatArray,
        val batchFiles: List<File>,
        val defNames: List<String>,
        /** Filename-safe base for exports, e.g. the specimen/reference name. */
        val baseName: String,
        val frameIndex: Int,
        val imgW: Int,
        val imgH: Int,
        val step: Int,
        /**
         * Per-frame step sizes for a parameter sweep, where each frame is a
         * different settings combination. Null for an ordinary analysis, whose
         * frames all share [step]. Its non-null-ness marks a sweep, which the
         * CSV export splits into subset/step/window/VSG columns.
         */
        val stepPerFrame: IntArray?,
        /** Per-frame subset sizes for a sweep; index-aligned with the frames. */
        val subsetPerFrame: IntArray?,
        /** Per-frame strain windows for a sweep; index-aligned with the frames. */
        val strainWindowPerFrame: IntArray?,
        val dataIndex: Int,
        val typeString: String,
        /** Display-scale bitmap (may be null while decode is in flight); exports prefer [refImagePath]. */
        val baseImage: Bitmap?,
        val refImagePath: String?,
        val defImagePaths: List<String>,
        /** Single-setting overview builder; null on a parameter sweep. */
        val summary: SummaryAnimation?,
        /** Whole-sequence colour bounds of a field, or null if it has no data. */
        val summaryBounds: (Int) -> Pair<Float, Float>?,
        /**
         * Report data for one frame, given that frame's index and decoded
         * field. Index-driven so an all-frames report can build each frame's
         * own cover — its parameters and its deformed image — rather than
         * reusing the one on screen.
         */
        val buildReportAt: (Int, FloatArray) -> com.indicvision.semper.report.ReportData?,
        val referenceName: String = "",
        val strainMethod: String = "VSG",
        val subset: Int = 41,
        val strainWindow: Int = 15,
        val roiX: Int = 0,
        val roiY: Int = 0,
        val roiW: Int = 0,
        val roiH: Int = 0,
    ) {
        /** Grid pitch of frame [index] — what rendering that frame depends on. */
        fun stepAt(index: Int): Int = stepPerFrame?.getOrNull(index) ?: step
    }

    private companion object {
        const val HEATMAP_ALPHA = 180

        val FIELDS = listOf(
            "U" to DicResult.IDX_U,
            "V" to DicResult.IDX_V,
            "Exx" to DicResult.IDX_EXX,
            "Eyy" to DicResult.IDX_EYY,
            "Exy" to DicResult.IDX_EXY,
        )

        const val KIND_PDF = "pdf"
        const val KIND_ZIP = "zip"
        const val KIND_CSV = "csv"
        const val KIND_PHOTOS = "photos"
        const val KIND_GIFS = "gifs"
    }
}
