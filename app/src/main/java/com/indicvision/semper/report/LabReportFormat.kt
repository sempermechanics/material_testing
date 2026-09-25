package com.indicvision.semper.report

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Number formatting for the lab report: fixed decimals and the journal's ×10ⁿ strains. */
object LabReportFormat {

    /** Fixed decimals, US locale, so a report reads the same on every phone. */
    fun num(value: Float, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", value)

    /**
     * A modulus in GPa for a results line: one decimal for metals (153.6), two
     * below 10 GPa, where one decimal would read PMMA's 2.00 and 2.09 as the
     * same "2.0" and "2.1" and hide the gap between two estimates.
     */
    fun gpa(value: Float): String = num(value, if (abs(value) < SMALL_GPA) 2 else 1)

    /**
     * The viewer's frame number (1-based) for 0-based deformed frames [first]
     * to [last]: "7", or "5–9" for a load held over several frames. The
     * table's S.No counts rows, which skips frames without a load or a field.
     */
    fun frameCell(first: Int, last: Int = first): String =
        if (last == first) "${first + 1}" else "${first + 1}–${last + 1}"

    /**
     * A strain the way the handwritten table writes it: "8.00×10⁻⁵". Zero and
     * non-finite values are written plainly.
     */
    fun sci(value: Float): String {
        if (value == 0f || !value.isFinite()) return num(value, 0)
        val exponent = floor(log10(abs(value.toDouble()))).toInt()
        var mantissa = value / 10.0.pow(exponent)
        var exp = exponent
        // Rounding 9.996 up to "10.00" should read 1.00×10ⁿ⁺¹.
        if (abs(String.format(Locale.US, "%.2f", mantissa).toDouble()) >= TEN) {
            mantissa /= TEN
            exp += 1
        }
        return "${String.format(Locale.US, "%.2f", mantissa)}×10${superscript(exp)}"
    }

    private const val SMALL_GPA = 10f

    private fun superscript(n: Int): String = n.toString().map { c ->
        when (c) {
            '-' -> '⁻'
            else -> SUPERSCRIPT_DIGITS[c - '0']
        }
    }.joinToString("")

    private const val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
    private const val TEN = 10.0
}
