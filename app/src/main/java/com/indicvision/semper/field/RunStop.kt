package com.indicvision.semper.field

/**
 * Why a run ended, as the `Int` stop code the app stores and sends today.
 *
 * The code travels as `SessionRecord.stopCode`, `ViewerArgs.stopCode` and the
 * `STOP_CODE` extra, `metadata.json`'s `metrics.stopCode`,
 * `BatchAnalysisOutcome.engineErrorCode`, the sweep runner's `errorCode` and
 * `SkippedNode.code`. [wireCode] is exactly that value, and [fromWireCode]
 * maps every `Int` back without loss: a code no case names becomes [Other],
 * so a newer engine's code survives a round trip through an older app.
 *
 * The named codes come from three places, pinned to them by test:
 * the engine's frozen return codes (`ENGINE_APP_CONTRACT.md` and
 * `full_field_path_c.cpp` for `-1`; `EngineFailure.ENGINE_ERROR_*`),
 * `AnalysisRunCodes`, and `VsgStudyRunner.ERROR_ENGINE_FAILED`.
 *
 * As a *stop* code, 0 is [Finished]. The engine also returns 0 as a point
 * count when a frame kept no points; `EngineFailure` reads that 0 as a
 * strain-window failure. That reading belongs to the point count, not to a
 * stop code, so it is not modelled here.
 */
sealed class RunStop(val wireCode: Int) {

    /** The run worked through every frame (stop code 0). */
    data object Finished : RunStop(FINISHED)

    /** Engine `-1`: AKAZE could not match the reference and the frame. */
    data object FeaturesUnmatched : RunStop(FEATURES_UNMATCHED)

    /** Engine `-2`: the ROI (or mask) left no grid points. */
    data object InvalidRoi : RunStop(INVALID_ROI)

    /** Engine `-3`: an image failed to decode, an argument was bad, or the output buffer would overrun. */
    data object InitFailed : RunStop(INIT_FAILED)

    /** App `-96`: two consecutive frames converged below the gate. */
    data object LowConvergence : RunStop(LOW_CONVERGENCE)

    /** App `-97`: the engine returned no usable field for a sweep combination. */
    data object SweepEngineFailed : RunStop(SWEEP_ENGINE_FAILED)

    /** App `-98`: saving the session would exceed the account quota. */
    data object SessionLimit : RunStop(SESSION_LIMIT)

    /** App and engine `-99`: the user cancelled. */
    data object Cancelled : RunStop(CANCELLED)

    /** Any other code, kept as is. Never one of the named codes. */
    data class Other(val code: Int) : RunStop(code) {
        init {
            require(code !in NAMED_CODES) { "Code $code has a named RunStop; use RunStop.fromWireCode" }
        }
    }

    /** True unless the run finished; `SessionRecord.stoppedEarly`'s `stopCode != 0`. */
    val stoppedEarly: Boolean get() = this != Finished

    companion object {
        private const val FINISHED = 0
        private const val FEATURES_UNMATCHED = -1
        private const val INVALID_ROI = -2
        private const val INIT_FAILED = -3
        private const val LOW_CONVERGENCE = -96
        private const val SWEEP_ENGINE_FAILED = -97
        private const val SESSION_LIMIT = -98
        private const val CANCELLED = -99

        private val NAMED_CODES = setOf(
            FINISHED,
            FEATURES_UNMATCHED,
            INVALID_ROI,
            INIT_FAILED,
            LOW_CONVERGENCE,
            SWEEP_ENGINE_FAILED,
            SESSION_LIMIT,
            CANCELLED,
        )

        /** Every named case, in wire-code order from 0 down. */
        val named: List<RunStop> by lazy {
            listOf(
                Finished,
                FeaturesUnmatched,
                InvalidRoi,
                InitFailed,
                LowConvergence,
                SweepEngineFailed,
                SessionLimit,
                Cancelled,
            )
        }

        /** The case for [code]; [Other] when no named case has it. Inverse of [wireCode]. */
        fun fromWireCode(code: Int): RunStop = when (code) {
            FINISHED -> Finished
            FEATURES_UNMATCHED -> FeaturesUnmatched
            INVALID_ROI -> InvalidRoi
            INIT_FAILED -> InitFailed
            LOW_CONVERGENCE -> LowConvergence
            SWEEP_ENGINE_FAILED -> SweepEngineFailed
            SESSION_LIMIT -> SessionLimit
            CANCELLED -> Cancelled
            else -> Other(code)
        }
    }
}
