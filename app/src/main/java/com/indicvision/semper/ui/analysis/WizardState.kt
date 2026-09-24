package com.indicvision.semper.ui.analysis

import android.os.Bundle
import androidx.annotation.WorkerThread
import com.indicvision.semper.data.SpecimenGeometry
import com.indicvision.semper.data.TestType
import com.indicvision.semper.data.WizardDraft
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

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
    private const val HAS_REFERENCE = "hasReference"
    private const val HAS_MASK = "hasMask"
    private const val HAS_CUSTOM_ROI = "hasCustomRoi"
    private const val ROI = "roi"
    private const val FRAME_COUNT = "frameCount"
    private const val ORDER_MODE = "orderMode"
    private const val ORDER_DIRECTION = "orderDirection"
    private const val FRAME_SIZE_ERROR = "frameSizeError"
    private const val FROM_VIDEO = "fromVideo"
    private const val SWEEP_MODE = "sweepMode"
    private const val SWEEP_RANGES = "sweepRanges"
    private const val SUBSET_OVERLAP = "subsetOverlap"
    private const val LINE_CUT_HORIZONTAL = "lineCutHorizontal"
    private const val VSG_FRAME_INDEX = "vsgFrameIndex"
    private const val WORKING_LOCAL_ID = "workingLocalId"
    private const val TEST_TYPE = "testType"
    private const val CROSS_SECTION_MM2 = "crossSectionMm2"
    private const val LOAD_AXIS_X = "loadAxisX"
    private const val GEOMETRY = "geometry"
    private const val HAS_LOAD_LOG = "hasLoadLog"
    private const val LOAD_CSV_NAME = "loadCsvName"
    private const val LOAD_LOG_START_S = "loadLogStartS"

    /**
     * Index-aligned lists behind the deformed-frames card; `-1` = size not
     * measured. [timesMs] is empty unless the frames came from a video.
     */
    @Serializable
    data class Frames(
        val paths: List<String> = emptyList(),
        val names: List<String> = emptyList(),
        val dates: List<Long> = emptyList(),
        val widths: List<Int> = emptyList(),
        val heights: List<Int> = emptyList(),
        val timesMs: List<Long> = emptyList(),
    )

    /** What [readInputs] read back from the draft. */
    class Inputs(val reference: ByteArray?, val mask: ByteArray?, val frames: Frames, val loadLog: String?)

    private val json = Json { ignoreUnknownKeys = true }

    fun save(vm: AnalysisViewModel): Bundle = Bundle().apply {
        putInt(STEP, vm.wizardStep)
        putBoolean(SETTINGS_REVIEWED, vm.settingsReviewed)
        putBoolean(SUBSET_USER_MODIFIED, vm.subsetUserModified)
        putInt(REF_W, vm.realRefWidth)
        putInt(REF_H, vm.realRefHeight)
        putString(REF_NAME, vm.refName)
        putBoolean(HAS_REFERENCE, vm.refBytes != null)
        putBoolean(HAS_MASK, vm.roiMaskBytes != null)
        putBoolean(HAS_CUSTOM_ROI, vm.hasCustomRoi)
        putIntArray(ROI, intArrayOf(vm.roiX, vm.roiY, vm.roiW, vm.roiH))
        putInt(FRAME_COUNT, vm.defFilePaths.size)
        putString(ORDER_MODE, vm.defOrderMode.name)
        putString(ORDER_DIRECTION, vm.defOrderDirection.name)
        putString(FRAME_SIZE_ERROR, vm.frameSizeError)
        putBoolean(FROM_VIDEO, vm.defFromVideo)
        putBoolean(SWEEP_MODE, vm.sweepMode)
        putIntArray(
            SWEEP_RANGES,
            intArrayOf(
                vm.subsetMin,
                vm.subsetMax,
                vm.strainWinMin,
                vm.strainWinMax,
                vm.subsetSamples,
                vm.strainWinSamples,
                vm.stepDenominator,
            ),
        )
        putDouble(SUBSET_OVERLAP, vm.subsetOverlap)
        putBoolean(LINE_CUT_HORIZONTAL, vm.lineCutHorizontal)
        putInt(VSG_FRAME_INDEX, vm.vsgFrameIndex)
        putString(WORKING_LOCAL_ID, vm.workingLocalId)
        putString(TEST_TYPE, vm.testType.wireName)
        putFloat(CROSS_SECTION_MM2, vm.crossSectionMm2)
        putBoolean(LOAD_AXIS_X, vm.loadAxisX)
        putFloatArray(GEOMETRY, vm.geometry.toArray())
        putBoolean(HAS_LOAD_LOG, vm.parsedLoadCsv != null)
        putString(LOAD_CSV_NAME, vm.loadCsvName)
        putFloat(LOAD_LOG_START_S, vm.loadLogStartS)
    }

    /** The scalars of a [save]d Bundle; the draft's parts follow through [readInputs]. */
    @Suppress("MagicNumber") // positions in the two packed arrays, in [save]'s order
    fun restoreScalars(vm: AnalysisViewModel, b: Bundle) {
        vm.wizardStep = b.getInt(STEP, 1)
        vm.settingsReviewed = b.getBoolean(SETTINGS_REVIEWED)
        vm.subsetUserModified = b.getBoolean(SUBSET_USER_MODIFIED)
        vm.realRefWidth = b.getInt(REF_W)
        vm.realRefHeight = b.getInt(REF_H)
        b.getString(REF_NAME)?.let { vm.refName = it }
        vm.hasCustomRoi = b.getBoolean(HAS_CUSTOM_ROI)
        b.getIntArray(ROI)?.takeIf { it.size == 4 }?.let {
            vm.roiX = it[0]
            vm.roiY = it[1]
            vm.roiW = it[2]
            vm.roiH = it[3]
        }
        b.getString(ORDER_MODE)?.let { name -> FrameOrderMode.entries.find { it.name == name } }
            ?.let { vm.defOrderMode = it }
        b.getString(ORDER_DIRECTION)?.let { name -> FrameOrderDirection.entries.find { it.name == name } }
            ?.let { vm.defOrderDirection = it }
        vm.frameSizeError = b.getString(FRAME_SIZE_ERROR)
        vm.defFromVideo = b.getBoolean(FROM_VIDEO)
        vm.sweepMode = b.getBoolean(SWEEP_MODE)
        b.getIntArray(SWEEP_RANGES)?.takeIf { it.size == 7 }?.let {
            vm.subsetMin = it[0]
            vm.subsetMax = it[1]
            vm.strainWinMin = it[2]
            vm.strainWinMax = it[3]
            vm.subsetSamples = it[4]
            vm.strainWinSamples = it[5]
            vm.stepDenominator = it[6]
        }
        vm.subsetOverlap = b.getDouble(SUBSET_OVERLAP, vm.subsetOverlap)
        vm.lineCutHorizontal = b.getBoolean(LINE_CUT_HORIZONTAL, true)
        vm.vsgFrameIndex = b.getInt(VSG_FRAME_INDEX, -1)
        vm.workingLocalId = b.getString(WORKING_LOCAL_ID)
        TestType.fromWire(b.getString(TEST_TYPE))?.let { vm.testType = it }
        vm.crossSectionMm2 = b.getFloat(CROSS_SECTION_MM2)
        vm.loadAxisX = b.getBoolean(LOAD_AXIS_X, true)
        vm.geometry = SpecimenGeometry.fromArray(b.getFloatArray(GEOMETRY))
        vm.loadCsvName = b.getString(LOAD_CSV_NAME).orEmpty()
        vm.loadLogStartS = b.getFloat(LOAD_LOG_START_S)
    }

    fun frames(vm: AnalysisViewModel): Frames = Frames(
        paths = vm.defFilePaths,
        names = vm.defOriginalNames,
        dates = vm.defFrameDates,
        widths = vm.defFilePaths.map { vm.defFrameSizes[it]?.first ?: -1 },
        heights = vm.defFilePaths.map { vm.defFrameSizes[it]?.second ?: -1 },
        timesMs = vm.defFrameTimesMs,
    )

    fun encodeFrames(frames: Frames): String = json.encodeToString(Frames.serializer(), frames)

    fun applyFrames(vm: AnalysisViewModel, frames: Frames) {
        vm.defFilePaths = frames.paths
        vm.defOriginalNames = frames.names
        vm.defFrameDates = frames.dates
        vm.defFrameTimesMs = frames.timesMs
        vm.defFrameSizes = frames.paths.indices
            .filter { frames.widths.getOrElse(it) { -1 } > 0 && frames.heights.getOrElse(it) { -1 } > 0 }
            .associate { frames.paths[it] to (frames.widths[it] to frames.heights[it]) }
    }

    /**
     * The draft parts [b] says the wizard held, or null when any is missing:
     * the reference, mask or load-log file, or one of the staged frames (the OS may
     * evict `cacheDir` under storage pressure). A partial restore would be a
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
        val frames = draft.readFrames()?.let(::decodeFrames) ?: Frames()
        val complete = (reference != null) == wantReference &&
            (mask != null) == wantMask &&
            (loadLog != null) == wantLoadLog &&
            frames.paths.size == b.getInt(FRAME_COUNT) &&
            frames.paths.all { File(it).isFile }
        return if (complete) Inputs(reference, mask, frames, loadLog) else null
    }

    private fun decodeFrames(text: String): Frames? = try {
        json.decodeFromString(Frames.serializer(), text)
    } catch (e: IllegalArgumentException) { // SerializationException included
        Timber.w(e, "Unreadable wizard draft frame list")
        null
    }
}
