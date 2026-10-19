package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.data.mechanical.CaptureStamp
import com.sempermechanics.semper.data.mechanical.MachineLoadTable
import com.sempermechanics.semper.data.mechanical.ParsedLoadCsv
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.mechanical.TypedLoads
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * The lab test's wizard inputs, one value on [AnalysisViewModel.lab]: what
 * the load card records (its logic is in LabInputs.kt). The view model's
 * properties of the same names read and write it ([LabField]), so the shared
 * wizard keeps one field for the fork's lab state rather than eleven.
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

/** An [AnalysisViewModel] property that is one field of its [AnalysisViewModel.lab]. */
internal class LabField<T>(
    private val read: (LabWizardState) -> T,
    private val write: (LabWizardState, T) -> LabWizardState,
) : ReadWriteProperty<AnalysisViewModel, T> {
    override fun getValue(thisRef: AnalysisViewModel, property: KProperty<*>): T = read(thisRef.lab)

    override fun setValue(thisRef: AnalysisViewModel, property: KProperty<*>, value: T) {
        thisRef.lab = write(thisRef.lab, value)
    }
}
