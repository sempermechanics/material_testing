package com.sempermechanics.semper.ui.viewer

import android.content.Context
import android.content.Intent
import com.sempermechanics.semper.data.mechanical.CurveCorrection
import com.sempermechanics.semper.data.mechanical.LabSession
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.FrameParams
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.VsgLatticeActivity
import com.sempermechanics.semper.ui.analysis.sweep.toSkippedNode
import com.sempermechanics.semper.ui.analysis.wizard.SweepSession
import timber.log.Timber

/**
 * Per-frame settings only a parameter sweep carries.
 *
 * Its presence is also what picks the destination: a sweep opens on the
 * interactive lattice, and a lattice node opens the result viewer with the
 * same arguments plus a [ViewerArgs.startFrame].
 */
data class ViewerSweepArgs(
    val subsets: List<Int>,
    val steps: List<Int>,
    val strainWindows: List<Int>,
    val lineCutHorizontal: Boolean,
    val skippedJson: String,
)

/**
 * Everything a viewer launch puts on its Intent, and everything a viewer reads
 * back (ADR-003).
 *
 * Every opened result is a saved row (ADR-022), so one builder writes it:
 * [of] a [SessionRecord], whether Home opens the row or the run that just
 * saved it does. The one result with no row, a sweep that solved nothing,
 * opens its lattice through [forFailedSweep]. Four places read it:
 * `ResultViewerActivity`, `VsgLatticeActivity`, `SettingsUsedSheet` and
 * `ViewerReportFactory`. All of them go through this type: writers through
 * [toIntent], readers through [from]. A missing extra used to read as whatever
 * default each reader picked (subset 41 in one place, 0 in another); now it is
 * filled from the session record, else from one default below, and logged.
 *
 * Deliberately **not** a `Parcelable` under a single extra: an Intent already
 * in the back stack across an update must keep opening, so the wire format is
 * the same twenty-odd [IntentKeys] it always was.
 */
data class ViewerArgs(
    val imgW: Int,
    val imgH: Int,
    val step: Int,
    val refName: String,
    val refPath: String,
    val batchDirPath: String?,
    val frameNames: List<String>,
    val stopCode: Int,
    val plannedFrames: Int,
    val sessionId: String?,
    val sessionLocalId: String?,
    val subsetSize: Int,
    val strainWindow: Int,
    val engineStats: List<Float>?,
    val roiX: Int,
    val roiY: Int,
    val roiW: Int,
    val roiH: Int,
    val sweep: ViewerSweepArgs? = null,
    // A run's own image paths, which an Intent of an older build may carry. A
    // row's frames are the originals persisted under the session dir, which
    // the viewer prefers anyway, so [of] writes neither, and an absent extra
    // and an empty one read the same there.
    val defPath: String? = null,
    val defFilePaths: List<String> = emptyList(),
    /**
     * The lab test as one value; its stress model is null on a plain DIC
     * session. On the Intent it is still its five extras, one key each
     * ([putLabSession], [Reader.readLabSession]), written unconditionally
     * (blank / 0 / empty when the session has none) so the two builders' key
     * sets stay identical and an Intent from an older build still reads.
     */
    val labSession: LabSession = LabSession.NONE,
    /** Each frame's load in N, index-aligned with [frameNames]; empty without machine loads. */
    val loadsN: List<Float> = emptyList(),
    /** The frame to open on; null opens a run on its summary. A lattice node sets it. */
    val startFrame: Int? = null,
    val strainMethod: String = STRAIN_METHOD_VSG,
    /** A video's frame times in its clip (ms), index-aligned with [frameNames]; null for anything else. */
    val frameTimesMs: List<Long>? = null,
) {

    /**
     * A sweep opens on its lattice unless a node has already been picked
     * ([startFrame]); everything else opens the result viewer.
     */
    fun toIntent(context: Context): Intent {
        val target = if (sweep != null && startFrame == null) {
            VsgLatticeActivity::class.java
        } else {
            ResultViewerActivity::class.java
        }
        return Intent(context, target).apply {
            putExtra(IntentKeys.IMG_W, imgW)
            putExtra(IntentKeys.IMG_H, imgH)
            putExtra(IntentKeys.STEP, step)
            putExtra(IntentKeys.REF_NAME, refName)
            putExtra(IntentKeys.REF_PATH, refPath)
            defPath?.let { putExtra(IntentKeys.DEF_PATH, it) }
            putExtra(IntentKeys.BATCH_DIR_PATH, batchDirPath)
            putStringArrayListExtra(IntentKeys.DEF_FILE_NAMES, ArrayList(frameNames))
            frameTimesMs?.let { putExtra(IntentKeys.FRAME_TIMES_MS, it.toLongArray()) }
            if (defFilePaths.isNotEmpty()) {
                putStringArrayListExtra(IntentKeys.DEF_FILE_PATHS, ArrayList(defFilePaths))
            }
            if (sweep != null) {
                putExtra(IntentKeys.SWEEP_SUBSETS, sweep.subsets.toIntArray())
                putExtra(IntentKeys.SWEEP_STEPS, sweep.steps.toIntArray())
                putExtra(IntentKeys.SWEEP_STRAIN_WINS, sweep.strainWindows.toIntArray())
                putExtra(IntentKeys.LINE_CUT_HORIZONTAL, sweep.lineCutHorizontal)
                putExtra(IntentKeys.SWEEP_SKIPPED, sweep.skippedJson)
            }
            putExtra(IntentKeys.STOP_CODE, stopCode)
            putExtra(IntentKeys.PLANNED_FRAMES, plannedFrames)
            putExtra(IntentKeys.SESSION_ID, sessionId)
            putExtra(IntentKeys.SESSION_LOCAL_ID, sessionLocalId)
            putExtra(IntentKeys.SUBSET_SIZE, subsetSize)
            putExtra(IntentKeys.STRAIN_WINDOW, strainWindow)
            putExtra(IntentKeys.STRAIN_METHOD, strainMethod)
            putExtra(IntentKeys.ENGINE_STATS, engineStats?.toFloatArray())
            putExtra(IntentKeys.ROI_X, roiX)
            putExtra(IntentKeys.ROI_Y, roiY)
            putExtra(IntentKeys.ROI_W, roiW)
            putExtra(IntentKeys.ROI_H, roiH)
            putLabSession(labSession)
            putExtra(IntentKeys.LOADS_N, loadsN.toFloatArray())
            startFrame?.let { putExtra(IntentKeys.START_FRAME, it) }
        }
    }

    /** [lab] as its five extras, the keys an older build wrote and reads. */
    private fun Intent.putLabSession(lab: LabSession) {
        putExtra(IntentKeys.TEST_TYPE, lab.testType)
        putExtra(IntentKeys.CROSS_SECTION_MM2, lab.crossSectionMm2)
        putExtra(IntentKeys.LOAD_AXIS_X, lab.loadAxisX)
        putExtra(IntentKeys.SPECIMEN_GEOMETRY, lab.geometry.toArray())
        putExtra(IntentKeys.CURVE_CORRECTION, lab.curveCorrection.toArray())
    }

    /** [engineStats] in the array form `EngineStats.fromArray` reads, or null. */
    fun engineStatsArray(): FloatArray? = engineStats?.toFloatArray()

    /** The reference's size, as the Intent gave it (0 × 0 when an old writer left it out). */
    val imageSize: ImageSize get() = ImageSize(imgW, imgH)

    /** The ROI the run solved over. */
    val roi: Roi get() = Roi(roiX, roiY, roiW, roiH)

    /** Every frame's solver parameters: the run's own, or a sweep's per-frame lists. */
    val frameParams: FrameParams
        get() = FrameParams(
            base = DicParams(subsetSize, step, strainWindow),
            subsets = sweep?.subsets.orEmpty(),
            steps = sweep?.steps.orEmpty(),
            strainWindows = sweep?.strainWindows.orEmpty(),
        )

    companion object {
        /** Step when neither the Intent nor a record says; the wizard's default. */
        const val DEFAULT_STEP = DicParams.DEFAULT_STEP

        /** Subset when neither the Intent nor a record says; the engine's default. */
        const val DEFAULT_SUBSET = DicParams.DEFAULT_SUBSET

        /**
         * Strain window when neither the Intent nor a record says. The wizard now
         * defaults to a 21 px VSG; readers keep 15 so a key-less Intent still opens
         * as it always did ([DicParams.DEFAULT_STRAIN_WINDOW]).
         */
        const val DEFAULT_STRAIN_WINDOW = DicParams.DEFAULT_STRAIN_WINDOW

        /** The only strain method the engine has. */
        const val STRAIN_METHOD_VSG = "VSG"

        /**
         * The viewer, or for a sweep its lattice, on the saved row [session]:
         * what Home opens, and what a run that just saved it opens. A sweep
         * names its frames after the combination behind them, and needs each
         * frame's own settings to render and describe it. The lab test rides
         * the row too ([SessionRecord.labSession], its loads when it has them),
         * so a result opened right after a run reads what Home's would.
         */
        fun of(session: SessionRecord): ViewerArgs = ViewerArgs(
            imgW = session.imgW,
            imgH = session.imgH,
            step = session.step,
            refName = session.refName,
            refPath = session.refPath,
            batchDirPath = session.sessionDir,
            frameNames = session.frameNames,
            stopCode = session.stopCode,
            plannedFrames = session.plannedFrameCount,
            sessionId = session.id,
            sessionLocalId = session.id,
            subsetSize = session.subset,
            strainWindow = session.strainWindow,
            engineStats = session.engineStats.ifEmpty { null },
            roiX = session.roiX,
            roiY = session.roiY,
            roiW = session.roiW,
            roiH = session.roiH,
            frameTimesMs = session.frameTimesMs.takeUnless { session.isSweep },
            sweep = if (session.isSweep) {
                ViewerSweepArgs(
                    subsets = session.sweepSubsets,
                    steps = session.sweepSteps,
                    strainWindows = session.sweepStrainWindows,
                    lineCutHorizontal = session.lineCutHorizontal,
                    skippedJson = SkippedNode.encodeJson(session.resolvedSkipNodes()),
                )
            } else {
                null
            },
            labSession = session.labSession,
            loadsN = if (session.hasMachineLoads) session.loadsN else emptyList(),
        )

        /**
         * The lattice of a sweep that solved no combination, [run]: the one
         * result no Home row holds. Every node of its plan failed, each with
         * its own reason (the run's codes, else the sweep's one code for every
         * node); the settings shown are its spec's, the plan's first
         * combination, and the frames are its launch's.
         */
        internal fun forFailedSweep(run: SweepSession): ViewerArgs {
            val result = run.result
            val sweep = checkNotNull(run.spec.sweep) { "not a sweep" }
            val settings = run.spec.recordSettings()
            val recorded = result.skipped.mapIndexed { i, point -> point.toSkippedNode(result.skippedCodes[i]) }
            val skipped = SkippedNode.forFailedSweep(recorded) {
                sweep.plan.map { it.toSkippedNode(result.engineErrorCode) }
            }
            return ViewerArgs(
                imgW = run.inputs.reference.size.width,
                imgH = run.inputs.reference.size.height,
                step = settings.step,
                refName = run.inputs.reference.name,
                refPath = "",
                batchDirPath = run.batchDir.absolutePath,
                frameNames = emptyList(),
                stopCode = result.engineErrorCode,
                plannedFrames = sweep.plan.size,
                sessionId = run.localSessionId,
                sessionLocalId = run.localSessionId,
                subsetSize = settings.subset,
                strainWindow = settings.strainWin,
                engineStats = result.firstMetrics?.toList(),
                roiX = settings.roiX,
                roiY = settings.roiY,
                roiW = settings.roiW,
                roiH = settings.roiH,
                sweep = ViewerSweepArgs(
                    subsets = emptyList(),
                    steps = emptyList(),
                    strainWindows = emptyList(),
                    lineCutHorizontal = sweep.lineCutHorizontal,
                    skippedJson = SkippedNode.encodeJson(skipped),
                ),
                defFilePaths = run.inputs.frames.paths,
                // A sweep records the test but no loads (LabInputs.mechanicalInputs).
                labSession = run.spec.mechanical.labSession,
            )
        }

        /**
         * A viewer over a bare directory of `.dat` frames with no session behind
         * it, the whole image as ROI: the debug and benchmark seed activities
         * and the Robolectric viewer tests.
         */
        @Suppress("LongParameterList") // each is a real knob of a seeded viewer
        fun ofFrames(
            batchDir: String,
            imgW: Int,
            imgH: Int,
            step: Int,
            frameNames: List<String> = emptyList(),
            startFrame: Int? = null,
        ) = ViewerArgs(
            imgW = imgW,
            imgH = imgH,
            step = step,
            refName = "",
            refPath = "",
            batchDirPath = batchDir,
            frameNames = frameNames,
            stopCode = 0,
            plannedFrames = frameNames.size,
            sessionId = null,
            sessionLocalId = null,
            subsetSize = DEFAULT_SUBSET,
            strainWindow = DEFAULT_STRAIN_WINDOW,
            engineStats = null,
            roiX = 0,
            roiY = 0,
            roiW = imgW,
            roiH = imgH,
            startFrame = startFrame,
        )

        /**
         * Parses a viewer Intent from any build.
         *
         * A missing key is filled from [record], else from its one default
         * (image size for the ROI's width and height, [DEFAULT_STEP],
         * [DEFAULT_SUBSET], [DEFAULT_STRAIN_WINDOW], axial loads, zero or empty
         * for the rest), and every filled key is logged. A key that is present wins
         * even when its value is null: the writer meant "none". [record] runs
         * only when a key is absent, which no current writer allows, so an
         * ordinary open costs no disk read. Sweep keys never fall back: their absence is what makes
         * an Intent a single run. The legacy `SWEEP_SKIP_*` arrays of older
         * builds fold into [ViewerSweepArgs.skippedJson].
         */
        fun from(intent: Intent, record: () -> SessionRecord? = { null }): ViewerArgs =
            Reader(intent, record).read()
    }

    /** One parse; collects which keys it had to fill, for the log line. */
    private class Reader(private val intent: Intent, record: () -> SessionRecord?) {
        private val record by lazy(record)
        private val fromRecord = mutableListOf<String>()
        private val defaulted = mutableListOf<String>()

        /**
         * The Intent's value when the key is there — a writer that put null
         * meant "none" — else the record's, else [default].
         */
        private fun <T> fill(key: String, ofRecord: (SessionRecord) -> T?, default: T, read: (Intent) -> T?): T {
            if (intent.hasExtra(key)) return read(intent) ?: default
            val filled = record?.let(ofRecord)
            (if (filled != null) fromRecord else defaulted).add(key)
            return filled ?: default
        }

        private fun int(key: String, ofRecord: (SessionRecord) -> Int?, default: Int): Int =
            fill(key, ofRecord, default) { it.getIntExtra(key, default) }

        private fun string(key: String, ofRecord: (SessionRecord) -> String?, default: String?): String? =
            fill(key, ofRecord, default) { it.getStringExtra(key) }

        fun read(): ViewerArgs {
            val imgW = int(IntentKeys.IMG_W, { it.imgW }, 0)
            val imgH = int(IntentKeys.IMG_H, { it.imgH }, 0)
            val args = ViewerArgs(
                imgW = imgW,
                imgH = imgH,
                step = int(IntentKeys.STEP, { it.step }, DEFAULT_STEP),
                refName = string(IntentKeys.REF_NAME, { it.refName }, "").orEmpty(),
                refPath = string(IntentKeys.REF_PATH, { it.refPath }, "").orEmpty(),
                batchDirPath = string(IntentKeys.BATCH_DIR_PATH, { it.sessionDir }, null),
                frameNames = fill(
                    IntentKeys.DEF_FILE_NAMES,
                    { if (it.isSweep) it.sweepLabels else it.defNames },
                    emptyList(),
                ) { it.getStringArrayListExtra(IntentKeys.DEF_FILE_NAMES) },
                stopCode = int(IntentKeys.STOP_CODE, { it.stopCode }, 0),
                plannedFrames = int(IntentKeys.PLANNED_FRAMES, { it.plannedFrameCount }, 0),
                sessionId = string(IntentKeys.SESSION_ID, { it.id }, null),
                sessionLocalId = intent.getStringExtra(IntentKeys.SESSION_LOCAL_ID),
                subsetSize = int(IntentKeys.SUBSET_SIZE, { it.subset }, DEFAULT_SUBSET),
                strainWindow = int(IntentKeys.STRAIN_WINDOW, { it.strainWindow }, DEFAULT_STRAIN_WINDOW),
                engineStats = fill(
                    IntentKeys.ENGINE_STATS,
                    { it.engineStats.ifEmpty { null } },
                    null,
                ) { it.getFloatArrayExtra(IntentKeys.ENGINE_STATS)?.toList() },
                roiX = int(IntentKeys.ROI_X, { it.roiX }, 0),
                roiY = int(IntentKeys.ROI_Y, { it.roiY }, 0),
                roiW = int(IntentKeys.ROI_W, { it.roiW }, imgW),
                roiH = int(IntentKeys.ROI_H, { it.roiH }, imgH),
                sweep = readSweep(),
                defPath = intent.getStringExtra(IntentKeys.DEF_PATH),
                defFilePaths = intent.getStringArrayListExtra(IntentKeys.DEF_FILE_PATHS).orEmpty(),
                startFrame = intent.takeIf { it.hasExtra(IntentKeys.START_FRAME) }
                    ?.getIntExtra(IntentKeys.START_FRAME, 0),
                strainMethod = intent.getStringExtra(IntentKeys.STRAIN_METHOD) ?: STRAIN_METHOD_VSG,
                labSession = readLabSession(),
                loadsN = fill(
                    IntentKeys.LOADS_N,
                    { if (it.hasMachineLoads) it.loadsN else emptyList() },
                    emptyList(),
                ) { it.getFloatArrayExtra(IntentKeys.LOADS_N)?.toList() },
                // Absent for anything but a video, so never filled from the record:
                // an older Intent's video frames keep their names.
                frameTimesMs = intent.getLongArrayExtra(IntentKeys.FRAME_TIMES_MS)?.toList(),
            )
            logMissingKeys()
            return args
        }

        /**
         * The lab test from its five extras, the inverse of [putLabSession]:
         * each missing one is filled from the record's own, so an Intent
         * that has only some of them still reads.
         */
        private fun readLabSession(): LabSession = LabSession(
            testType = string(IntentKeys.TEST_TYPE, { it.testType }, "").orEmpty(),
            crossSectionMm2 = fill(IntentKeys.CROSS_SECTION_MM2, { it.crossSectionMm2 }, 0f) {
                it.getFloatExtra(IntentKeys.CROSS_SECTION_MM2, 0f)
            },
            loadAxisX = fill(IntentKeys.LOAD_AXIS_X, { it.loadAxisX }, true) {
                it.getBooleanExtra(IntentKeys.LOAD_AXIS_X, true)
            },
            geometry = fill(IntentKeys.SPECIMEN_GEOMETRY, { it.geometry }, SpecimenGeometry.NONE) {
                SpecimenGeometry.fromArray(it.getFloatArrayExtra(IntentKeys.SPECIMEN_GEOMETRY))
            },
            curveCorrection = fill(IntentKeys.CURVE_CORRECTION, { it.curveCorrection }, CurveCorrection.NONE) {
                CurveCorrection.fromArray(it.getFloatArrayExtra(IntentKeys.CURVE_CORRECTION))
            },
        )

        /** Names the extras that came from the session's record or a default, once per read. */
        private fun logMissingKeys() {
            if (fromRecord.isNotEmpty() || defaulted.isNotEmpty()) {
                Timber.w("Viewer Intent missing keys; from record: %s; defaulted: %s", fromRecord, defaulted)
            }
        }

        private fun readSweep(): ViewerSweepArgs? {
            val subsets = intent.getIntArrayExtra(IntentKeys.SWEEP_SUBSETS) ?: return null
            val skipped = SkippedNode.decodeFromExtras(
                intent.getStringExtra(IntentKeys.SWEEP_SKIPPED),
                intent.getIntArrayExtra(IntentKeys.SWEEP_SKIP_SUBSETS),
                intent.getIntArrayExtra(IntentKeys.SWEEP_SKIP_STEPS),
                intent.getIntArrayExtra(IntentKeys.SWEEP_SKIP_STRAIN_WINS),
                intent.getIntArrayExtra(IntentKeys.SWEEP_SKIP_CODES),
            )
            return ViewerSweepArgs(
                subsets = subsets.toList(),
                steps = intent.getIntArrayExtra(IntentKeys.SWEEP_STEPS)?.toList().orEmpty(),
                strainWindows = intent.getIntArrayExtra(IntentKeys.SWEEP_STRAIN_WINS)?.toList().orEmpty(),
                lineCutHorizontal = fill(IntentKeys.LINE_CUT_HORIZONTAL, { it.lineCutHorizontal }, true) {
                    it.getBooleanExtra(IntentKeys.LINE_CUT_HORIZONTAL, true)
                },
                skippedJson = SkippedNode.encodeJson(skipped),
            )
        }
    }
}
