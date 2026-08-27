package com.indicvision.semper.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Locale

/**
 * The strain floor this session's frames were captured at, carried from the
 * test-shot burst through to the report and the CSV.
 *
 * It is stored rather than merely shown because the number outlives the moment
 * it was measured in. A result exported months later has to still say what floor
 * it was captured at and whether that floor was above the limit its strain
 * numbers are only meaningful under — an override that leaves no trace is how a
 * bad number becomes a published number.
 *
 * Absent (null on the record) for any session whose frames did not come from
 * this app's capture flow. An import has no burst to measure, and inventing a
 * floor for one would be worse than admitting there is none.
 */
@Serializable
data class CaptureNoiseFloor(
    /** The floor in microstrain, at [vsgPx]. A floor without its gauge means nothing. */
    val microstrain: Double,
    /** Gauge length the floor is quoted at, in pixels. */
    val vsgPx: Double,
    /** Displacement noise the floor was derived from, in pixels. */
    val sigmaPx: Double,
    /** Frames the burst managed. Fewer weakens the verdict, not the floor. */
    val frames: Int,
    /**
     * True when the floor came back above the limit results can be trusted
     * under. This is the whole reason the type is persisted.
     */
    val exceeded: Boolean,
    /** True when the user saw [exceeded] and chose to record anyway. */
    val overridden: Boolean,

    /**
     * Image noise variance `D(η)` measured on the burst, or NaN when it could
     * not be. This is what the subset recommendation solves against for a
     * captured run, in place of a 2008 lab camera's constant.
     */
    val noiseVariance: Double = Double.NaN,

    /**
     * Neighbour correlation of the difference image, or NaN. Above
     * [DENOISE_CORRELATION] the phone is smoothing frames underneath the
     * pipeline lockdown, which makes [noiseVariance] read better than the
     * camera really is.
     */
    val noiseCorrelation: Double = Double.NaN,
) {

    /** The floor in the unit that reads plainly at its own magnitude. */
    fun label(): String = NoiseFloorText.floorLabel(microstrain)

    /**
     * True when the frames were smoothed by something the pipeline lockdown
     * could not switch off.
     *
     * A measurement that did not come back is not evidence of a clean camera,
     * so NaN reads as false here: the user is told nothing rather than told
     * something reassuring.
     */
    fun denoised(): Boolean =
        noiseCorrelation.isFinite() && noiseCorrelation > DENOISE_CORRELATION

    /** The floor with the evidence behind it, for a report line. */
    fun detail(): String = String.format(
        Locale.US,
        "%s (%.3f px over a %.0f px gauge, %d frames)",
        label(),
        sigmaPx,
        vsgPx,
        frames,
    )

    /**
     * The sentence a reader of the report must not be able to miss, or null when
     * the floor was within limits and there is nothing to warn about.
     *
     * Phrased as what the number means rather than as a status, because a reader
     * coming to this months later needs to know which of their strain values to
     * distrust, not that a dialog once appeared.
     */
    fun warning(): String? = when {
        !exceeded -> null
        overridden -> "Recorded past this floor. Strain smaller than ${label()} is noise, not measurement."
        else -> "Strain smaller than ${label()} is noise, not measurement."
    }

    /**
     * The measured image noise, as the report states it, or null when the burst
     * could not measure it.
     *
     * The smoothing finding rides on the same line as the number it qualifies,
     * because separated they invite the wrong reading: a low `D` on its own
     * looks like a good camera, and that is exactly the mistake smoothing
     * produces.
     */
    fun noiseDetail(): String? {
        if (!noiseVariance.isFinite()) return null
        val measured = String.format(Locale.US, "%.1f grey levels²", noiseVariance)
        return if (denoised()) "$measured (frames were smoothed)" else measured
    }

    /** JSON, for the one Intent extra that carries this from capture to the wizard. */
    fun encode(): String = Json.encodeToString(this)

    companion object {
        /**
         * Neighbour correlation above which the frames have been smoothed.
         *
         * Real sensor noise is independent pixel to pixel and lands near zero,
         * a little above it because demosaic and the YUV downscale both mix
         * neighbours slightly — that floor is small and much the same on any
         * phone. Active spatial denoising replaces each pixel with a weighted
         * average of its neighbours and drives this far higher, so the gap is
         * wide and the line between them is not delicate.
         *
         * Its only consequence is a sentence. The subset recommendation is
         * protected by clamping instead — see
         * [com.indicvision.semper.ui.analysis.SubsetRecommender.thresholdFor] —
         * so a threshold set a little wrong here costs a warning, never a run.
         */
        const val DENOISE_CORRELATION = 0.5

        /**
         * Recover a floor from [encode]'s output.
         *
         * Anything unreadable comes back null rather than throwing: a session
         * that loses its floor is a session with no floor recorded, which is
         * the same state every imported analysis is already in. Failing the
         * whole hand-off over it would trade a missing note for a lost run.
         */
        fun decode(json: String?): CaptureNoiseFloor? {
            if (json.isNullOrBlank()) return null
            return try {
                Json.decodeFromString<CaptureNoiseFloor>(json)
            } catch (e: SerializationException) {
                Timber.w(e, "capture floor: unreadable, continuing without it")
                null
            }
        }
    }
}
