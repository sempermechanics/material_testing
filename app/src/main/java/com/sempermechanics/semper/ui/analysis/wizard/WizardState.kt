package com.sempermechanics.semper.ui.analysis.wizard

import android.os.Bundle
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.mechanical.TypedLoads
import com.sempermechanics.semper.data.prefs.WizardDraft
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.getRoi
import com.sempermechanics.semper.field.putRoi
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderDirection
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderMode
import com.sempermechanics.semper.ui.analysis.sweep.SweepRanges
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * The wizard's editing state across a process death (ADR-005).
 *
 * Split by size. The scalars go in the view model's `SavedStateHandle` as one
 * Bundle ([save] / [restoreScalars]) and are back before the Activity's first
 * frame. The frame list (up to 500 paths, too big for a Bundle), the
 * reference bytes, the mask and the machine load log go in the [WizardDraft]
 * and come back through [readInputs], off the main thread.
 *
 * The settings sliders are not here: they are views with ids, and the
 * Activity's own saved view state restores them.
 */
internal object WizardState {

    /** The `SavedStateHandle` key the whole Bundle lives under. */
    const val KEY = "wizard_state"

    private const val STEP = "step"
    private const val SETTINGS_REVIEWED = "settingsReviewed"
    private const val SUBSET_USER_MODIFIED = "subsetUserModified"
    private const val REF_W = "refW"
    private const val REF_H = "refH"
    private const val REF_NAME = "refName"
    private const val REF_CAPTURE_MS = "refCaptureMs"
    private const val HAS_REFERENCE = "hasReference"
    private const val HAS_MASK = "hasMask"
    private const val HAS_CUSTOM_ROI = "hasCustomRoi"
    private const val ROI = "roi"
    private const val FRAME_COUNT = "frameCount"

    /**
     * SHA-256 of the frame list [save] was handed, as the draft should hold
     * it. Absent in a Bundle an older app saved; [readInputs] then falls back
     * to [FRAME_COUNT] alone.
     */
    private const val FRAMES_FINGERPRINT = "framesFingerprint"
    private const val ORDER_MODE = "orderMode"
    private const val ORDER_DIRECTION = "orderDirection"
    private const val FRAME_SIZE_ERROR = "frameSizeError"
    private const val FROM_VIDEO = "fromVideo"
    private const val SWEEP_MODE = "sweepMode"
    private const val SWEEP_RANGES = "sweepRanges"
    private const val SUBSET_OVERLAP = "subsetOverlap"
    private const val LINE_CUT_HORIZONTAL = "lineCutHorizontal"
    private const val SWEEP_FRAME_INDEX = "vsgFrameIndex"
    private const val WORKING_LOCAL_ID = "workingLocalId"
    private const val TEST_TYPE = "testType"
    private const val CROSS_SECTION_MM2 = "crossSectionMm2"
    private const val LOAD_AXIS_X = "loadAxisX"
    private const val GEOMETRY = "geometry"
    private const val HAS_LOAD_LOG = "hasLoadLog"
    private const val LOAD_CSV_NAME = "loadCsvName"
    private const val LOAD_LOG_START_S = "loadLogStartS"
    private const val TYPED_LOADS_ENTRY = "typedLoadsEntry"

    /**
     * Index-aligned lists behind the deformed-frames card; `-1` = size not
     * measured. [timesMs] is empty unless the frames came from a video;
     * [captureTimesMs] is empty unless they are photos (null: no EXIF time).
     * [typedLoadsKg] is bending's typed hanger mass per frame (null: blank).
     */
    @Serializable
    data class Frames(
        val paths: List<String> = emptyList(),
        val names: List<String> = emptyList(),
        val dates: List<Long> = emptyList(),
        val widths: List<Int> = emptyList(),
        val heights: List<Int> = emptyList(),
        val timesMs: List<Long> = emptyList(),
        val captureTimesMs: List<Long?> = emptyList(),
        val typedLoadsKg: List<Float?> = emptyList(),
    )

    /** What [readInputs] read back from the draft. */
    class Inputs(val reference: ByteArray?, val mask: ByteArray?, val frames: Frames, val loadLog: String?)

    private val json = Json { ignoreUnknownKeys = true }

    /** The scalars of [viewModel], with the fingerprint of [framesJson], the list queued for the draft. */
    fun save(viewModel: AnalysisViewModel, framesJson: String): Bundle = Bundle().apply {
        putInt(STEP, viewModel.wizardStep)
        putBoolean(SETTINGS_REVIEWED, viewModel.settingsReviewed)
        putBoolean(SUBSET_USER_MODIFIED, viewModel.subsetUserModified)
        putInt(REF_W, viewModel.refSize.width)
        putInt(REF_H, viewModel.refSize.height)
        putString(REF_NAME, viewModel.refName)
        viewModel.refCaptureTimeMs?.let { putLong(REF_CAPTURE_MS, it) }
        putBoolean(HAS_REFERENCE, viewModel.refBytes != null)
        putBoolean(HAS_MASK, viewModel.roiMaskBytes != null)
        putBoolean(HAS_CUSTOM_ROI, viewModel.hasCustomRoi)
        putRoi(ROI, viewModel.roi)
        putInt(FRAME_COUNT, viewModel.deformedFrames.size)
        putString(ORDER_MODE, viewModel.defOrderMode.name)
        putString(ORDER_DIRECTION, viewModel.defOrderDirection.name)
        putString(FRAME_SIZE_ERROR, viewModel.frameSizeError)
        putBoolean(FROM_VIDEO, viewModel.defFromVideo)
        putBoolean(SWEEP_MODE, viewModel.sweepMode)
        putIntArray(SWEEP_RANGES, viewModel.sweepRanges.toIntArray())
        putDouble(SUBSET_OVERLAP, viewModel.subsetOverlap)
        putBoolean(LINE_CUT_HORIZONTAL, viewModel.lineCutHorizontal)
        putInt(SWEEP_FRAME_INDEX, viewModel.sweepFrameIndex)
        putString(WORKING_LOCAL_ID, viewModel.workingLocalId)
        putString(FRAMES_FINGERPRINT, fingerprint(framesJson))
        putString(TEST_TYPE, viewModel.testType.wireName)
        putFloat(CROSS_SECTION_MM2, viewModel.crossSectionMm2)
        putBoolean(LOAD_AXIS_X, viewModel.loadAxisX)
        putFloatArray(GEOMETRY, viewModel.geometry.toArray())
        putBoolean(HAS_LOAD_LOG, viewModel.parsedLoadCsv != null)
        putString(LOAD_CSV_NAME, viewModel.loadCsvName)
        putFloat(LOAD_LOG_START_S, viewModel.loadLogStartS)
        putString(TYPED_LOADS_ENTRY, viewModel.typedLoadsEntry.name)
    }

    /** The scalars of a [save]d Bundle; the draft's parts follow through [readInputs]. */
    fun restoreScalars(viewModel: AnalysisViewModel, b: Bundle) {
        viewModel.wizardStep = b.getInt(STEP, 1)
        viewModel.settingsReviewed = b.getBoolean(SETTINGS_REVIEWED)
        viewModel.subsetUserModified = b.getBoolean(SUBSET_USER_MODIFIED)
        viewModel.refSize = ImageSize(b.getInt(REF_W), b.getInt(REF_H))
        b.getString(REF_NAME)?.let { viewModel.refName = it }
        viewModel.refCaptureTimeMs = if (b.containsKey(REF_CAPTURE_MS)) b.getLong(REF_CAPTURE_MS) else null
        viewModel.hasCustomRoi = b.getBoolean(HAS_CUSTOM_ROI)
        b.getRoi(ROI)?.let { viewModel.roi = it }
        b.getString(ORDER_MODE)?.let { name -> FrameOrderMode.entries.find { it.name == name } }
            ?.let { viewModel.defOrderMode = it }
        b.getString(ORDER_DIRECTION)?.let { name -> FrameOrderDirection.entries.find { it.name == name } }
            ?.let { viewModel.defOrderDirection = it }
        viewModel.frameSizeError = b.getString(FRAME_SIZE_ERROR)
        viewModel.defFromVideo = b.getBoolean(FROM_VIDEO)
        viewModel.sweepMode = b.getBoolean(SWEEP_MODE)
        SweepRanges.fromIntArray(b.getIntArray(SWEEP_RANGES))?.let { viewModel.sweepRanges = it }
        viewModel.subsetOverlap = b.getDouble(SUBSET_OVERLAP, viewModel.subsetOverlap)
        viewModel.lineCutHorizontal = b.getBoolean(LINE_CUT_HORIZONTAL, true)
        viewModel.sweepFrameIndex = b.getInt(SWEEP_FRAME_INDEX, -1)
        viewModel.workingLocalId = b.getString(WORKING_LOCAL_ID)
        restoreLabScalars(viewModel, b)
    }

    /** The mechanical test's scalars of a [save]d Bundle. */
    private fun restoreLabScalars(viewModel: AnalysisViewModel, b: Bundle) {
        TestType.fromWire(b.getString(TEST_TYPE))?.let { viewModel.testType = it }
        viewModel.crossSectionMm2 = b.getFloat(CROSS_SECTION_MM2)
        viewModel.loadAxisX = b.getBoolean(LOAD_AXIS_X, true)
        viewModel.geometry = SpecimenGeometry.fromArray(b.getFloatArray(GEOMETRY))
        viewModel.loadCsvName = b.getString(LOAD_CSV_NAME).orEmpty()
        viewModel.loadLogStartS = b.getFloat(LOAD_LOG_START_S)
        b.getString(TYPED_LOADS_ENTRY)?.let { name -> TypedLoads.Entry.entries.find { it.name == name } }
            ?.let { viewModel.typedLoadsEntry = it }
    }

    fun frames(viewModel: AnalysisViewModel): Frames = wizardFramesOf(viewModel.deformedFrames)

    fun encodeFrames(frames: Frames): String = json.encodeToString(Frames.serializer(), frames)

    /**
     * The draft parts [b] says the wizard held, or null when any is missing:
     * the reference, mask or load-log file, one of the staged frames (the OS may evict
     * `cacheDir` under storage pressure), or the frame list itself. The list
     * must be the very one [save] fingerprinted, not merely as long: a kill
     * before its write landed leaves the previous list, which may hold the
     * same number of frames in another order. A partial restore would be a
     * wizard that looks ready but solves something else, so it is all or none.
     */
    @WorkerThread
    fun readInputs(b: Bundle, draft: WizardDraft): Inputs? {
        val wantReference = b.getBoolean(HAS_REFERENCE)
        val wantMask = b.getBoolean(HAS_MASK)
        val reference = if (wantReference) draft.readReference() else null
        val mask = if (wantMask) draft.readMask() else null
        val wantLoadLog = b.getBoolean(HAS_LOAD_LOG)
        val loadLog = if (wantLoadLog) draft.readLoadLog() else null
        val framesText = draft.readFrames()
        val frames = framesText?.let(::decodeFrames) ?: Frames()
        val expected = b.getString(FRAMES_FINGERPRINT)
        val sameList = expected == null || expected == fingerprint(framesText ?: encodeFrames(Frames()))
        val complete = (reference != null) == wantReference &&
            (mask != null) == wantMask &&
            (loadLog != null) == wantLoadLog &&
            sameList &&
            frames.paths.size == b.getInt(FRAME_COUNT) &&
            frames.paths.all { File(it).isFile }
        return if (complete) Inputs(reference, mask, frames, loadLog) else null
    }

    /** Hex SHA-256 of [framesJson]'s UTF-8 bytes. */
    fun fingerprint(framesJson: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(framesJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun decodeFrames(text: String): Frames? = try {
        json.decodeFromString(Frames.serializer(), text)
    } catch (e: IllegalArgumentException) { // SerializationException included
        Timber.w(e, "Unreadable wizard draft frame list")
        null
    }
}
