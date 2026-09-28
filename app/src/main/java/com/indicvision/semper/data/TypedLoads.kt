package com.indicvision.semper.data

/**
 * Loads the student types in on the phone: the mass on the bending hanger for
 * each deformed frame, in kilograms. A hand-loaded beam has no machine and so
 * no log; these become the same [MachineLoadTable] a CSV gives, in newtons.
 *
 * Pure Kotlin — no Android imports.
 */
object TypedLoads {

    /** Standard gravity, m/s²: W (N) = m (kg) × [G]. */
    const val G = 9.80665f

    /** Stored as the session's `loadSource` where a CSV stores its file name. Not shown. */
    const val SOURCE = "typed in the app (kg)"

    /**
     * One load per deformed frame from the typed masses, index-aligned with
     * the frames. A box not yet typed (null) is NaN, as a frame with no load;
     * the wizard does not go on while any is (a photo with no weight is 0 kg).
     * Null when there are no frames or nothing is typed.
     */
    fun toTable(kg: List<Float?>, frameCount: Int): MachineLoadTable? {
        if (frameCount <= 0 || kg.none { it != null }) return null
        val loadsN = List(frameCount) { index -> kg.getOrNull(index)?.let { it * G } ?: Float.NaN }
        return MachineLoadTable(loadsN, LoadMapping.TYPED_KG, emptyList(), kg.count { it != null })
    }

    /**
     * A typed box as a mass in kg. Blank is [Parsed.Blank]; a decimal comma is
     * accepted, since that is what a keypad in many locales offers.
     */
    fun parseKg(text: String): Parsed {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Parsed.Blank
        val value = trimmed.replace(',', '.').toFloatOrNull()
        return if (value == null || !value.isFinite() || value < 0f) Parsed.Invalid else Parsed.Kg(value)
    }

    sealed interface Parsed {
        data object Blank : Parsed
        data object Invalid : Parsed
        data class Kg(val kg: Float) : Parsed
    }
}
