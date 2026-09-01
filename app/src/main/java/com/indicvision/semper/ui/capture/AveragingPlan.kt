package com.indicvision.semper.ui.capture

/**
 * How many stills to average into each frame of a run.
 *
 * Averaging *k* independent frames of a scene that is holding still divides
 * the displacement noise by `sqrt(k)` — the single largest remaining lever
 * once the pipeline is frozen, and the reason it is worth spending an
 * interval's spare time on. It only works because loading here is
 * quasi-static: the frames inside one group have to be of the same state, and
 * they are, because a group takes a fraction of a second while a held step
 * lasts seconds.
 *
 * **k is derived, never offered.** There is no precision mode to pick and no
 * slider to set: the user's chosen rate already implies an interval, this
 * counts how many captures fit inside it at the exposure and resolution
 * already decided, and the run says in one line what it chose.
 *
 * **Quality per frame is fixed first; k fills what is left.** The obvious way
 * to fit more captures into an interval is to shorten the exposure and raise
 * ISO to compensate — and it is exactly wrong. Raising ISO raises `D(eta)`,
 * `sigma_u` scales with `sqrt(D(eta))`, so each frame gets noisier by roughly
 * the factor averaging more of them removes; and a shorter exposure breaks the
 * whole-flicker-period multiple [ExposurePlan] establishes, which is a real
 * loss for no gain at all. Nothing here reads or returns an exposure, an ISO
 * or a frame size, which is what makes that guarantee structural rather than
 * a rule someone has to remember.
 *
 * Pure arithmetic, no Android types.
 */
internal object AveragingPlan {

    /**
     * Most stills averaged into one frame.
     *
     * Sixteen is a 4x noise reduction, and the curve is flattening: reaching
     * 8x would cost 64 captures per frame, which is no longer a group of
     * frames of one state but a run of its own. It also bounds the accumulator
     * and the worst-case time a single frame can take, both of which matter
     * more than the last fraction of a stop.
     */
    const val MAX_FRAMES = 16

    /**
     * Share of the interval a group is allowed to occupy.
     *
     * The rest is deliberately left empty. The per-frame cost this is planned
     * against is a measurement, not a guarantee — a thermal step or a slow
     * write makes one capture late — and a group that overruns its interval
     * pushes the whole run off the rate the user was promised. Leaving nearly
     * a third of every interval unclaimed is what keeps averaging free: it can
     * only ever use time the run was going to spend waiting.
     */
    const val USABLE_FRACTION = 0.7

    /**
     * Stills to average for one frame of a run.
     *
     * @param intervalMs gap between frame start times, as the run is paced.
     * @param perFrameMs measured cost of one still on this device at this
     *   resolution — [CaptureCalibration]'s number, not an estimate.
     * @param steady whether the noise burst found the scene holding still. A
     *   setup that would not settle, or that drifted, is one where the frames
     *   inside a group are not of the same state; averaging them then blurs
     *   the pattern instead of quieting it, and blur is indistinguishable from
     *   a bad speckle pattern in the result.
     * @return 1 when averaging does not fit or is not safe, which is exactly
     *   today's behaviour.
     */
    fun framesFor(intervalMs: Long, perFrameMs: Long, steady: Boolean): Int {
        if (!steady || intervalMs <= 0L || perFrameMs <= 0L) return 1
        val budgetMs = intervalMs * USABLE_FRACTION
        val fits = (budgetMs / perFrameMs).toInt()
        return fits.coerceIn(1, MAX_FRAMES)
    }

    /**
     * The noise reduction [framesFor] bought, as a factor.
     *
     * Reported rather than assumed: it is what the measured floor has to be
     * divided by before it describes the run, and only when the burst came
     * back steady — drift and correlated pipeline terms do not average down at
     * all, so crediting `1/sqrt(k)` there would understate the floor of the
     * very setup that needed it stated honestly.
     */
    fun noiseGain(frames: Int): Double =
        if (frames <= 1) 1.0 else kotlin.math.sqrt(frames.toDouble())
}
