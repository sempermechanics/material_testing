package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.data.mechanical.BeamEdgeTaps
import com.sempermechanics.semper.data.mechanical.LoadMapping
import com.sempermechanics.semper.data.mechanical.MachineLoadMapper
import com.sempermechanics.semper.data.mechanical.MechanicalTestInputs
import com.sempermechanics.semper.data.mechanical.ParsedLoadCsv
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.mechanical.TypedLoads
import com.sempermechanics.semper.report.StressStrain
import com.sempermechanics.semper.ui.analysis.load.PhotoCaptureTime

/*
 * The lab test's inputs on the wizard's view model: the load log or typed
 * loads, matched to the deformed frames, and what the run records about the
 * test. The state lives on [AnalysisViewModel] (it rides `WizardState` and the
 * draft across a process death, ADR-005); the logic is here, as extensions,
 * like the runner files beside it.
 */

/** How the logged loads will become stress, given what is entered so far. */
fun AnalysisViewModel.stressModel(): StressStrain.Model =
    StressStrain.Model.of(testType.wireName, crossSectionMm2, loadAxisX, geometry)

/**
 * Re-matches the load log to the frames as they are now; with no log,
 * bending's typed loads. Cheap; call after either changes.
 */
fun AnalysisViewModel.refreshMachineLoads() {
    val parsed = parsedLoadCsv
    machineLoads = when {
        parsed != null ->
            MachineLoadMapper.map(parsed, defFilePaths.size, frameTimesForLoads(), testType, loadLogStartS)
        testType == TestType.BENDING -> TypedLoads.toTable(typedLoadsKg, defFilePaths.size)
        else -> null
    }
}

/** Typed loads replace an imported log: one source at a time. */
fun AnalysisViewModel.setTypedLoads(kg: List<Float?>) {
    if (parsedLoadCsv != null) clearMachineLoads()
    typedLoadsKg = kg
    refreshMachineLoads()
}

/** Bending's typed loads are the source: something is typed and no log is imported. */
fun AnalysisViewModel.hasTypedLoads(): Boolean =
    testType == TestType.BENDING && parsedLoadCsv == null && typedLoadsKg.any { it != null }

fun AnalysisViewModel.clearTypedLoads() {
    typedLoadsKg = emptyList()
    refreshMachineLoads()
}

/**
 * Each deformed frame's time after the reference: a video's own, else
 * the photos' capture times when the reference and every frame have one.
 * Empty when neither is known, which matches the log by row order.
 */
private fun AnalysisViewModel.frameTimesForLoads(): List<Long> =
    defFrameTimesMs.ifEmpty { PhotoCaptureTime.relativeTimesMs(refCaptureTimeMs, defCaptureTimesMs) }

/** An imported load log. Its [text] goes to the draft so a process death can parse it again. */
fun AnalysisViewModel.setLoadLog(csv: ParsedLoadCsv, name: String, text: String) {
    typedLoadsKg = emptyList()
    parsedLoadCsv = csv
    loadCsvName = name
    drafts.stage { it.writeLoadLog(text) }
}

fun AnalysisViewModel.clearMachineLoads() {
    parsedLoadCsv = null
    loadCsvName = ""
    loadLogStartS = 0f
    machineLoads = null
    drafts.stage { it.writeLoadLog(null) }
}

/**
 * A test with a load log needs a load per frame and every dimension its
 * stress model uses before the wizard can go on.
 */
fun AnalysisViewModel.mechanicalInputsReady(): Boolean =
    !testType.hasMachineLoad ||
        (
            machineLoads.let { it != null && it.matchedFrames > 0 } &&
                typedLoadsMissing() == 0 &&
                stressModel().isComplete &&
                !loadPointMissing()
            )

/**
 * Photos with typed loads still waiting for theirs. Every photo needs a
 * mass, 0 for no weight: an empty box is a load not yet typed, never "no
 * load". Zero while the loads come from a CSV.
 */
fun AnalysisViewModel.typedLoadsMissing(): Int {
    val table = machineLoads?.takeIf { it.mapping == LoadMapping.TYPED_KG } ?: return 0
    return defFilePaths.size - table.matchedFrames
}

/**
 * Bending reads its scale and its deflection from the beam's edges tapped
 * on the reference photo; without them there is no δ and no E.
 */
fun AnalysisViewModel.loadPointMissing(): Boolean = testType == TestType.BENDING && !geometry.loadPoint.isSet

/**
 * A new reference photo puts the beam somewhere else, so taps on the old
 * one no longer mark its edges. Call wherever [AnalysisViewModel.refBytes] is replaced.
 */
fun AnalysisViewModel.onReferenceReplaced() {
    if (geometry.loadPoint != BeamEdgeTaps.NONE) geometry = geometry.copy(loadPoint = BeamEdgeTaps.NONE)
}

/**
 * Everything the session record stores about the test. A sweep varies
 * settings on one frame pair, so it records the type and dimensions but never
 * per-frame loads — there is no load-per-combination to plot.
 *
 * Plain 2D DIC records no test at all: a blank type is what every reader
 * (viewer, CSV, PDF cover, cloud metadata) already takes as a plain DIC
 * session, so the run saves exactly what it did before test types existed.
 */
fun AnalysisViewModel.mechanicalInputs(forSweep: Boolean): MechanicalTestInputs = if (!testType.hasMachineLoad) {
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

/** Back to no lab inputs: no load log, no dimensions. The test type stays. */
fun AnalysisViewModel.clearLabInputs() {
    refCaptureTimeMs = null
    clearMachineLoads()
    crossSectionMm2 = 0f
    geometry = SpecimenGeometry.NONE
}
