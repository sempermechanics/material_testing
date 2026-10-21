package com.sempermechanics.semper.ui.analysis.wizard

import android.os.Bundle
import com.sempermechanics.semper.data.mechanical.CaptureStamp
import com.sempermechanics.semper.data.mechanical.MachineLoadTable
import com.sempermechanics.semper.data.mechanical.ParsedLoadCsv
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.mechanical.TypedLoads

/**
 * The lab test's wizard inputs, one value on [AnalysisViewModel.lab]: what
 * the load card records (its logic is in LabInputs.kt). It is one field of
 * [WizardInputs] ([WizardInputs.lab]), read as [AnalysisViewModel.lab] and
 * changed like every other wizard input, one [AnalysisViewModel.edit] per
 * change ([editLab]), so the shared wizard keeps one field for the fork's lab
 * state rather than eleven.
 *
 * @property testType chosen on Home before the media picker, so the wizard
 *   knows on open whether to ask for machine loads. A wizard reached without
 *   the extra (a stale launcher shortcut) is plain 2D DIC: Semper's wizard,
 *   no load card.
 * @property crossSectionMm2 specimen cross-section in mm², typed on the load
 *   card; 0 until it is.
 * @property loadAxisX strain axis for the stress–strain curve: Exx (true) or Eyy.
 * @property geometry bending dimensions typed on the load card;
 *   [SpecimenGeometry.NONE] until they are.
 * @property parsedLoadCsv the machine's load log as read from the file, kept
 *   so a change to the deformed frames re-runs the match without re-reading
 *   the document. Null until a file is imported (or after Clear).
 * @property loadCsvName display name of the imported log; blank when none.
 * @property machineLoads [parsedLoadCsv] matched to the current deformed
 *   frames (or bending's typed loads); null when either is missing.
 * @property refCapture EXIF capture time of the picked reference photo,
 *   with whether it carried sub-seconds; null for a video frame or a photo
 *   without one.
 * @property refVideoTimeMs the video reference frame's time in its clip, ms;
 *   null for a photo. A video frame's time after the reference, which a timed
 *   load log is matched by, is its `DeformedFrame.timeMs` less this.
 * @property loadLogStartS seconds after the reference frame at which the load
 *   log's first row was taken — the gap between pressing record and the
 *   machine starting its log. Only the time match reads it.
 * @property typedLoadsEntry how the typed-loads sheet reads its boxes: each
 *   photo's total, or what changed since the photo before. Only how they are
 *   typed; the typed loads are always stored as totals.
 */
data class LabWizardState(
    val testType: TestType = TestType.DIC_2D,
    val crossSectionMm2: Float = 0f,
    val loadAxisX: Boolean = true,
    val geometry: SpecimenGeometry = SpecimenGeometry.NONE,
    val parsedLoadCsv: ParsedLoadCsv? = null,
    val loadCsvName: String = "",
    val machineLoads: MachineLoadTable? = null,
    val refCapture: CaptureStamp? = null,
    val refVideoTimeMs: Long? = null,
    val loadLogStartS: Float = 0f,
    val typedLoadsEntry: TypedLoads.Entry = TypedLoads.Entry.ABSOLUTE,
)

/** The lab test's inputs as they are now: [WizardInputs.lab] of [AnalysisViewModel.inputs]. */
val AnalysisViewModel.lab: LabWizardState get() = inputs.value.lab

/** Changes only the lab test's inputs, in one [AnalysisViewModel.edit]. */
internal fun AnalysisViewModel.editLab(transform: (LabWizardState) -> LabWizardState) =
    edit { it.copy(lab = transform(it.lab)) }

// The lab test's scalars in the wizard's saved-state Bundle (WizardState, ADR-005).
// The load log's text goes to the draft; HAS_LOAD_LOG says whether to read it back.
private const val REF_CAPTURE_MS = "refCaptureMs"

/** Whether [REF_CAPTURE_MS] carried sub-seconds; absent when unknown (a Bundle an older app saved). */
private const val REF_CAPTURE_SUB_SEC = "refCaptureSubSec"
private const val REF_VIDEO_MS = "refVideoMs"
private const val TEST_TYPE = "testType"
private const val CROSS_SECTION_MM2 = "crossSectionMm2"
private const val LOAD_AXIS_X = "loadAxisX"
private const val GEOMETRY = "geometry"
private const val HAS_LOAD_LOG = "hasLoadLog"
private const val LOAD_CSV_NAME = "loadCsvName"
private const val LOAD_LOG_START_S = "loadLogStartS"
private const val TYPED_LOADS_ENTRY = "typedLoadsEntry"

/** Writes [lab]'s scalars into this Bundle, under the keys the fork has always used. */
internal fun Bundle.putLab(lab: LabWizardState) {
    lab.refCapture?.let { stamp ->
        putLong(REF_CAPTURE_MS, stamp.ms)
        stamp.subSeconds?.let { putBoolean(REF_CAPTURE_SUB_SEC, it) }
    }
    lab.refVideoTimeMs?.let { putLong(REF_VIDEO_MS, it) }
    putString(TEST_TYPE, lab.testType.wireName)
    putFloat(CROSS_SECTION_MM2, lab.crossSectionMm2)
    putBoolean(LOAD_AXIS_X, lab.loadAxisX)
    putFloatArray(GEOMETRY, lab.geometry.toArray())
    putBoolean(HAS_LOAD_LOG, lab.parsedLoadCsv != null)
    putString(LOAD_CSV_NAME, lab.loadCsvName)
    putFloat(LOAD_LOG_START_S, lab.loadLogStartS)
    putString(TYPED_LOADS_ENTRY, lab.typedLoadsEntry.name)
}

/**
 * [lab] with the scalars [putLab] wrote. The test type and the typed-loads
 * entry keep [lab]'s when the Bundle lacks or garbles them; the load log and
 * its match come back from the draft ([WizardState.readInputs]).
 */
internal fun Bundle.restoreLab(lab: LabWizardState): LabWizardState = lab.copy(
    refCapture = if (containsKey(REF_CAPTURE_MS)) {
        CaptureStamp(
            getLong(REF_CAPTURE_MS),
            subSeconds = if (containsKey(REF_CAPTURE_SUB_SEC)) getBoolean(REF_CAPTURE_SUB_SEC) else null,
        )
    } else {
        null
    },
    refVideoTimeMs = if (containsKey(REF_VIDEO_MS)) getLong(REF_VIDEO_MS) else null,
    testType = TestType.fromWire(getString(TEST_TYPE)) ?: lab.testType,
    crossSectionMm2 = getFloat(CROSS_SECTION_MM2),
    loadAxisX = getBoolean(LOAD_AXIS_X, true),
    geometry = SpecimenGeometry.fromArray(getFloatArray(GEOMETRY)),
    loadCsvName = getString(LOAD_CSV_NAME).orEmpty(),
    loadLogStartS = getFloat(LOAD_LOG_START_S),
    typedLoadsEntry = getString(TYPED_LOADS_ENTRY)?.let { name -> TypedLoads.Entry.entries.find { it.name == name } }
        ?: lab.typedLoadsEntry,
)

/** Whether the Bundle says the draft holds a load log ([putLab]). */
internal fun Bundle.wantsLoadLog(): Boolean = getBoolean(HAS_LOAD_LOG)
