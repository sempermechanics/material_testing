package com.indicvision.semper.data

import java.util.Locale

/**
 * Turns a measured strain floor into the one number the user is shown.
 *
 * Kept separate and pure because it is the only technical limit the user cannot
 * be shielded from, so how it reads matters: a floor quoted in microstrain when
 * it is really several millistrain hides how bad it is, and a floor quoted to
 * four significant figures pretends to a precision the measurement does not have.
 */
internal object NoiseFloorText {

    /**
     * A floor in the unit that reads plainly at its own magnitude: microstrain
     * below 1 mε, millistrain above it, both to a resolution a person can act on.
     */
    fun floorLabel(microstrain: Double): String {
        if (!microstrain.isFinite() || microstrain < 0.0) return UNKNOWN
        return if (microstrain < MICRO_PER_MILLI) {
            String.format(Locale.US, "%.0f µε", microstrain)
        } else {
            String.format(Locale.US, "%.1f mε", microstrain / MICRO_PER_MILLI)
        }
    }

    private const val MICRO_PER_MILLI = 1_000.0
    private const val UNKNOWN = "—"
}
