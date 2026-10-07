package com.sempermechanics.semper.data

/**
 * Loads the student types in on the phone: the mass on the bending hanger for
 * each deformed frame, in kilograms. A hand-loaded beam has no machine and so
 * no log; these become the same [MachineLoadTable] a CSV gives, in newtons.
 * They may be typed as totals or as what changed per photo ([Entry]); either
 * way they are kept as totals.
 *
 * Pure Kotlin — no Android imports.
 */
object TypedLoads {

    /** Standard gravity, m/s²: W (N) = m (kg) × [G]. */
    const val G = 9.80665f

    /** A load W (N) as the mass that weighs it, W / [G], in kg. */
    fun kg(loadN: Float): Float = loadN / G

    /**
     * (x, W N) graph points as (x, kg). Bending shows load in kg, what the
     * student hung — graphs, tables and captions; the maths runs in newtons
     * and the CSV/JSON exports stay in newtons.
     */
    fun loadsInKg(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> = points.map { (x, w) -> x to kg(w) }

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
     * accepted, since that is what a keypad in many locales offers. Only an
     * [Entry.INCREMENTAL] box may be negative: a weight taken off the hanger.
     */
    fun parseKg(text: String, entry: Entry = Entry.ABSOLUTE): Parsed {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Parsed.Blank
        val value = trimmed.replace(',', '.').toFloatOrNull()
            ?.takeIf { it.isFinite() && (it >= 0f || entry == Entry.INCREMENTAL) }
        return if (value == null) Parsed.Invalid else Parsed.Kg(value)
    }

    /**
     * The hanger total of each box as [entry] reads it, in kg. An incremental
     * box adds to the last total known before it (the empty hanger for the
     * first). Null for a box that is blank, not a number, or whose total
     * falls below 0; a blank or bad box adds nothing to the ones after it.
     * [fallback] stands in for a box that is not a number or goes below 0
     * (not for a blank), and is then the total the next box adds to.
     */
    fun totals(boxes: List<Parsed>, entry: Entry, fallback: List<Float?> = emptyList()): List<Float?> {
        var last = 0f
        return boxes.mapIndexed { index, box ->
            if (box is Parsed.Blank) return@mapIndexed null
            val total = (box as? Parsed.Kg)
                ?.let { if (entry == Entry.INCREMENTAL) last + it.kg else it.kg }
                ?.takeIf { it >= 0f }
                ?: fallback.getOrNull(index)
            if (total != null) last = total
            total
        }
    }

    /**
     * Totals as increments: each less the last total known before it (0, the
     * empty hanger, for the first). A blank stays blank. [runningTotals] of
     * the result gives the totals back, so switching [Entry] loses nothing.
     */
    fun increments(totalsKg: List<Float?>): List<Float?> {
        var last = 0f
        return totalsKg.map { total ->
            if (total == null) return@map null
            val step = total - last
            last = total
            step
        }
    }

    /**
     * Increments as totals, the inverse of [increments]: each is the last total
     * known before it plus its own. Unlike [totals] nothing is checked, so a
     * total below 0 stays, for the absolute box to flag.
     */
    fun runningTotals(steps: List<Float?>): List<Float?> {
        var last = 0f
        return steps.map { step ->
            if (step == null) return@map null
            last += step
            last
        }
    }

    /** How the boxes read: the total on the hanger, or what changed since the previous photo. */
    enum class Entry { ABSOLUTE, INCREMENTAL }

    sealed interface Parsed {
        data object Blank : Parsed
        data object Invalid : Parsed
        data class Kg(val kg: Float) : Parsed
    }
}
