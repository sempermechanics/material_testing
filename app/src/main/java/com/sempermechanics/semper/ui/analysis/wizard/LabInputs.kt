// The lab inputs' logic as AnalysisViewModel extensions, one small function per
// step of the load card's flow; hence TooManyFunctions is suppressed here.
@file:Suppress("TooManyFunctions")

package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.data.mechanical.BeamEdgeTaps
import com.sempermechanics.semper.data.mechanical.CaptureStamp
import com.sempermechanics.semper.data.mechanical.LoadMapping
import com.sempermechanics.semper.data.mechanical.MachineLoadMapper
import com.sempermechanics.semper.data.mechanical.MechanicalTestInputs
import com.sempermechanics.semper.data.mechanical.ParsedLoadCsv
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.mechanical.TypedLoads
import com.sempermechanics.semper.report.StressStrain
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.run.RunSpec

/*
 * The lab test's inputs on the wizard's view model: the load log or typed
 * loads, matched to the deformed frames, and what the run records about the
 * test. The state is [WizardInputs.lab] (it rides `WizardState` and the draft
 * across a process death, ADR-005). The logic is here: pure functions of
 * [WizardInputs] / [LabWizardState], and the view model's extensions that
 * read them or apply them in one [AnalysisViewModel.edit], like the runner
 * files beside it.
 */

/**
 * Time of each deformed frame after the reference, index-aligned with
 * [WizardInputs.deformedFrames]: a video frame's clip time ([DeformedFrame.timeMs]) less the
 * reference's ([LabWizardState.refVideoTimeMs]; 0 when unknown). Image batches leave it
 * empty; their times come from [defCaptureStamps] and [LabWizardState.refCapture].
 */
val WizardInputs.defTimesAfterRefMs: List<Long>
    get() {
        val ref = lab.refVideoTimeMs ?: 0L
        return deformedFrames.map { (it.timeMs ?: return emptyList()) - ref }
    }

/** These inputs with each frame's time after the reference set to [value]'s ([defTimesAfterRefMs]). */
fun WizardInputs.withDefTimesAfterRefMs(value: List<Long>): WizardInputs {
    val ref = lab.refVideoTimeMs ?: 0L
    return copy(deformedFrames = deformedFrames.mapIndexed { i, f -> f.copy(timeMs = value.getOrNull(i)?.plus(ref)) })
}

/**
 * EXIF capture time of each picked photo, with its precision
 * ([DeformedFrame.captureStamp]), index-aligned with [WizardInputs.deformedFrames]; null for
 * a photo without one. Empty for video frames.
 */
val WizardInputs.defCaptureStamps: List<CaptureStamp?>
    get() = if (defFromVideo) emptyList() else deformedFrames.map { it.captureStamp }

/** These inputs with each photo's capture time set to [value]'s ([defCaptureStamps]). */
fun WizardInputs.withDefCaptureStamps(value: List<CaptureStamp?>): WizardInputs = copy(
    deformedFrames = deformedFrames.mapIndexed { i, f ->
        val stamp = value.getOrNull(i)
        f.copy(captureTimeMs = stamp?.ms, captureSubSec = stamp?.subSeconds)
    },
)

/**
 * Bending's hanger mass per deformed frame, in kg, typed on the phone
 * ([DeformedFrame.typedLoadKg]); index-aligned with [WizardInputs.deformedFrames] and
 * reordered with it. Null is a box left blank. Empty when nothing is
 * typed. Used only while no load log is imported: the two are never
 * combined.
 */
val WizardInputs.typedLoadsKg: List<Float?>
    get() = deformedFrames.map { it.typedLoadKg }.takeIf { kg -> kg.any { it != null } } ?: emptyList()

/** These inputs with bending's typed masses set to [value]'s ([typedLoadsKg]). */
fun WizardInputs.withTypedLoadsKg(value: List<Float?>): WizardInputs =
    copy(deformedFrames = deformedFrames.mapIndexed { i, f -> f.copy(typedLoadKg = value.getOrNull(i)) })

/** How the logged loads will become stress, given what is entered so far. */
fun LabWizardState.stressModel(): StressStrain.Model =
    StressStrain.Model.of(testType.wireName, crossSectionMm2, loadAxisX, geometry)

/**
 * These inputs with the load log matched to the frames as they are now;
 * with no log, bending's typed loads. Cheap; apply after either changes.
 */
fun WizardInputs.withMachineLoadsMatched(): WizardInputs {
    val parsed = lab.parsedLoadCsv
    val frameCount = deformedFrames.size
    val table = when {
        // A video's frames carry clip times; a photo batch is matched from
        // its capture times, coarse when any was read without sub-seconds.
        parsed != null && defTimesAfterRefMs.isNotEmpty() ->
            MachineLoadMapper.map(parsed, frameCount, defTimesAfterRefMs, lab.testType, lab.loadLogStartS)
        parsed != null -> MachineLoadMapper.mapPhotos(
            parsed,
            frameCount,
            lab.refCapture,
            defCaptureStamps,
            lab.testType,
            lab.loadLogStartS,
        )
        lab.testType == TestType.BENDING -> TypedLoads.toTable(typedLoadsKg, frameCount)
        else -> null
    }
    return copy(lab = lab.copy(machineLoads = table))
}

/** Bending's typed loads are the source: something is typed and no log is imported. */
fun WizardInputs.hasTypedLoads(): Boolean =
    lab.testType == TestType.BENDING && lab.parsedLoadCsv == null && typedLoadsKg.any { it != null }

/**
 * A test with a load log needs a load per frame and every dimension its
 * stress model uses before the wizard can go on.
 */
fun WizardInputs.mechanicalInputsReady(): Boolean =
    !lab.testType.hasMachineLoad ||
        (
            lab.machineLoads.let { it != null && it.matchedFrames > 0 } &&
                typedLoadsMissing() == 0 &&
                lab.stressModel().isComplete &&
                !lab.loadPointMissing()
            )

/**
 * Photos with typed loads still waiting for theirs. Every photo needs a
 * mass, 0 for no weight: an empty box is a load not yet typed, never "no
 * load". Zero while the loads come from a CSV.
 */
fun WizardInputs.typedLoadsMissing(): Int {
    val table = lab.machineLoads?.takeIf { it.mapping == LoadMapping.TYPED_KG } ?: return 0
    return deformedFrames.size - table.matchedFrames
}

/**
 * Bending reads its scale and its deflection from the beam's edges tapped
 * on the reference photo; without them there is no δ and no E.
 */
fun LabWizardState.loadPointMissing(): Boolean = testType == TestType.BENDING && !geometry.loadPoint.isSet

/**
 * A new reference photo puts the beam somewhere else, so taps on the old
 * one no longer mark its edges. Apply wherever [WizardInputs.refBytes] is replaced.
 */
fun LabWizardState.withoutBeamTaps(): LabWizardState =
    if (geometry.loadPoint != BeamEdgeTaps.NONE) copy(geometry = geometry.copy(loadPoint = BeamEdgeTaps.NONE)) else this

/** No load log, no match: what Clear leaves. */
fun LabWizardState.withoutLoadLog(): LabWizardState =
    copy(parsedLoadCsv = null, loadCsvName = "", loadLogStartS = 0f, machineLoads = null)

/** Back to no lab inputs: no load log, no dimensions. The test type stays. */
fun LabWizardState.cleared(): LabWizardState = withoutLoadLog().copy(
    refCapture = null,
    refVideoTimeMs = null,
    crossSectionMm2 = 0f,
    geometry = SpecimenGeometry.NONE,
)

/**
 * Everything the session record stores about the test. A sweep varies
 * settings on one frame pair, so it records the type and dimensions but never
 * per-frame loads — there is no load-per-combination to plot.
 *
 * Plain 2D DIC records no test at all: a blank type is what every reader
 * (viewer, CSV, PDF cover, cloud metadata) already takes as a plain DIC
 * session, so the run saves exactly what it did before test types existed.
 */
fun LabWizardState.mechanicalInputs(forSweep: Boolean): MechanicalTestInputs = if (!testType.hasMachineLoad) {
    MechanicalTestInputs.NONE
} else {
    MechanicalTestInputs(
        testType = testType.wireName,
        crossSectionMm2 = crossSectionMm2,
        loadAxisX = loadAxisX,
        geometry = geometry,
        loadsN = if (forSweep) emptyList() else machineLoads?.loadsN.orEmpty(),
        loadSource = when {
            forSweep -> ""
            machineLoads?.mapping == LoadMapping.TYPED_KG -> TypedLoads.SOURCE
            else -> loadCsvName
        },
        loadMapping = if (forSweep) "" else machineLoads?.mapping?.name.orEmpty(),
    )
}

/**
 * The run's test type as analytics names it ([TestType.wireName]): from the
 * spec frozen at Compute, so a run on the engine thread never reads the
 * wizard's inputs. Plain 2D DIC records no type, so it reads as [TestType.DIC_2D].
 */
internal val RunSpec.testTypeWire: String
    get() = (TestType.fromWire(mechanical.testType) ?: TestType.DIC_2D).wireName

// The view model's side: reads of its inputs, and changes made in one edit.

/** [WizardInputs.defTimesAfterRefMs] of the inputs as they are now. */
val AnalysisViewModel.defTimesAfterRefMs: List<Long> get() = inputs.value.defTimesAfterRefMs

/** [WizardInputs.defCaptureStamps] of the inputs as they are now. */
val AnalysisViewModel.defCaptureStamps: List<CaptureStamp?> get() = inputs.value.defCaptureStamps

/** [WizardInputs.typedLoadsKg] of the inputs as they are now. */
val AnalysisViewModel.typedLoadsKg: List<Float?> get() = inputs.value.typedLoadsKg

/** How the logged loads will become stress, given what is entered so far. */
fun AnalysisViewModel.stressModel(): StressStrain.Model = lab.stressModel()

/** Re-matches the load log (or the typed loads) to the frames as they are now; see [withMachineLoadsMatched]. */
fun AnalysisViewModel.refreshMachineLoads() = edit { it.withMachineLoadsMatched() }

/** Typed loads replace an imported log: one source at a time. */
fun AnalysisViewModel.setTypedLoads(kg: List<Float?>) {
    if (lab.parsedLoadCsv != null) clearMachineLoads()
    edit { it.withTypedLoadsKg(kg).withMachineLoadsMatched() }
}

/** [WizardInputs.hasTypedLoads] of the inputs as they are now. */
fun AnalysisViewModel.hasTypedLoads(): Boolean = inputs.value.hasTypedLoads()

fun AnalysisViewModel.clearTypedLoads() = edit { it.withTypedLoadsKg(emptyList()).withMachineLoadsMatched() }

/** An imported load log. Its [text] goes to the draft so a process death can parse it again. */
fun AnalysisViewModel.setLoadLog(csv: ParsedLoadCsv, name: String, text: String) {
    edit { it.withTypedLoadsKg(emptyList()).copy(lab = it.lab.copy(parsedLoadCsv = csv, loadCsvName = name)) }
    drafts.stage { it.writeLoadLog(text) }
}

fun AnalysisViewModel.clearMachineLoads() {
    editLab { it.withoutLoadLog() }
    drafts.stage { it.writeLoadLog(null) }
}

/** [WizardInputs.mechanicalInputsReady] of the inputs as they are now. */
fun AnalysisViewModel.mechanicalInputsReady(): Boolean = inputs.value.mechanicalInputsReady()

/** [WizardInputs.typedLoadsMissing] of the inputs as they are now. */
fun AnalysisViewModel.typedLoadsMissing(): Int = inputs.value.typedLoadsMissing()

/** [LabWizardState.loadPointMissing] of the inputs as they are now. */
fun AnalysisViewModel.loadPointMissing(): Boolean = lab.loadPointMissing()

/** A new reference: the beam's taps go ([LabWizardState.withoutBeamTaps]). */
fun AnalysisViewModel.onReferenceReplaced() = editLab { it.withoutBeamTaps() }

/** [LabWizardState.mechanicalInputs] of the inputs as they are now. */
fun AnalysisViewModel.mechanicalInputs(forSweep: Boolean): MechanicalTestInputs = lab.mechanicalInputs(forSweep)

/** Back to no lab inputs ([LabWizardState.cleared]), the load log dropped from the draft too. */
fun AnalysisViewModel.clearLabInputs() {
    editLab { it.cleared() }
    drafts.stage { it.writeLoadLog(null) }
}
