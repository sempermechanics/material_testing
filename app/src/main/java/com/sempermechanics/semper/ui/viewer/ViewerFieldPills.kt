package com.sempermechanics.semper.ui.viewer

import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.FieldKind

/**
 * The five field pills, in screen order, as one id ↔ [FieldKind] mapping.
 *
 * The layout has to check something, and it checks U. The active field survives
 * rotation in the ViewModel, so the pills must be re-derived from it on setup —
 * otherwise an Exx heatmap comes back with the U pill lit.
 */
object ViewerFieldPills {

    /** Pill id → its field. */
    val BY_ID: Map<Int, FieldKind> = mapOf(
        R.id.rbFieldU to FieldKind.U,
        R.id.rbFieldV to FieldKind.V,
        R.id.rbFieldExx to FieldKind.EXX,
        R.id.rbFieldEyy to FieldKind.EYY,
        R.id.rbFieldExy to FieldKind.EXY,
    )

    /** The pill showing [field]. */
    fun idFor(field: FieldKind): Int = BY_ID.entries.first { it.value == field }.key
}
