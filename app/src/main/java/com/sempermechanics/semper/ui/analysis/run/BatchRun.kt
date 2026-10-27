package com.sempermechanics.semper.ui.analysis.run

import androidx.annotation.MainThread
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.ui.analysis.rerun.RerunSetup
import com.sempermechanics.semper.ui.analysis.rerun.snapshotRerunSetup
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.WizardInputs
import com.sempermechanics.semper.ui.analysis.wizard.WorkingSession
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * One batch run: what it solves ([spec]) and against what ([inputs]), the
 * import cache its frames came from ([cacheDir]), when it started, and the
 * [job] whose cancel it checks between frames.
 */
internal class BatchRun(
    val spec: RunSpec,
    val cacheDir: File,
    val startedAtMs: Long,
    val job: CoroutineContext,
    val inputs: RunInputs,
)

/**
 * The images a run solves, frozen on the main thread when it is launched
 * ([snapshotRunInputs]), so the run on the native thread reads nothing the
 * wizard may write meanwhile (TD-171). The rest of what it solves is its
 * [RunSpec] (ADR-004); the session it writes is resolved inside the run,
 * after the quota check, from the wizard's [working] session as it stood at
 * launch (`openRunSession`).
 */
internal class RunInputs(
    val reference: Reference,
    val frames: Frames,
    /** What a saved run keeps for Home's Re-run beyond its spec and images; null keeps nothing. */
    val setup: RerunSetup? = null,
    /** The wizard's working session at launch ([WizardInputs.working]); null for none. */
    val working: WorkingSession? = null,
) {

    /**
     * The reference image: its encoded [bytes] (null if none was picked, which
     * the run reports as it always has), its true pixel [size], and the [name]
     * the user picked it as. The array is shared, not copied: the wizard
     * replaces its reference, never writes into it.
     */
    class Reference(val bytes: ByteArray?, val size: ImageSize, val name: String)

    /**
     * The deformed frames, index-aligned: each one's staged [paths] entry, the
     * name the user picked it as, its time in its clip ([timesMs], null unless
     * every frame has one) and the clip itself ([videoName], null for picked
     * images).
     */
    class Frames(
        val paths: List<String>,
        val originalNames: List<String>,
        val timesMs: List<Long>?,
        val videoName: String?,
    )
}

/** The wizard's reference and frames as they stand now, copied so later edits do not reach a run. */
@MainThread
internal fun AnalysisViewModel.snapshotRunInputs(): RunInputs = inputs.value.let { now ->
    RunInputs(
        reference = RunInputs.Reference(now.refBytes, now.refSize, now.refName),
        frames = RunInputs.Frames(
            paths = defFilePaths.toList(),
            originalNames = defOriginalNames.toList(),
            timesMs = defFrameTimesMs?.toList(),
            videoName = now.defVideoName,
        ),
        setup = snapshotRerunSetup(),
        working = now.working,
    )
}
