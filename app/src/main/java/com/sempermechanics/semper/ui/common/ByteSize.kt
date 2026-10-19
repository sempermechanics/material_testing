package com.sempermechanics.semper.ui.common

import java.util.Locale

/** Each [ByteUnit] is this many of the one below it (1 KB = 1024 bytes). */
private const val BINARY_STEP = 1024L

/**
 * The unit every byte count in the app is shown in: binary (1 KB = 1024
 * bytes), the largest that keeps the count at 1 or more, KB at least. Settings'
 * sizes, Home's backups card and rows, the transfer banner and the export
 * dialog all count through it, so one count reads the same on every screen.
 */
enum class ByteUnit(val bytes: Long, val label: String) {
    KB(BINARY_STEP, "KB"),
    MB(BINARY_STEP * BINARY_STEP, "MB"),
    GB(BINARY_STEP * BINARY_STEP * BINARY_STEP, "GB"),
    ;

    /** [count] bytes counted in this unit. */
    fun amount(count: Long): Double = count / bytes.toDouble()

    companion object {
        /** The unit [count] is shown in. */
        fun of(count: Long): ByteUnit = entries.lastOrNull { count >= it.bytes } ?: KB
    }
}

/**
 * A byte count as Settings and Home show it: in its [ByteUnit], always in
 * `Locale.US` digits, GB to one decimal and the smaller units whole —
 * "1.5 GB", "120 MB", "64 KB".
 */
object ByteSize {

    fun format(bytes: Long): String {
        val unit = ByteUnit.of(bytes)
        val pattern = if (unit == ByteUnit.GB) "%.1f %s" else "%.0f %s"
        return String.format(Locale.US, pattern, unit.amount(bytes), unit.label)
    }
}
