package com.indicvision.semper.ui.analysis

import kotlin.math.abs

/**
 * Where a tap lands in [BeamEdgeTapActivity]. The first tap is the top edge
 * and sets the load point's x; the bottom edge always shares that x, so the
 * second tap only chooses how far down it is. Thickness is then measured
 * straight across the beam, whatever the finger's sideways slip.
 *
 * Once both are placed, a tap moves the mark nearer in height: the top moves
 * freely (and carries the bottom's x with it), the bottom only up or down.
 *
 * "Top" is the first tap, wherever it lands: tapping the bottom edge first
 * gives the same thickness and midpoint with the two swapped, and the report
 * signs δ by the load, not by this order.
 */
internal object BeamTapPlacement {

    data class Mark(val x: Float, val y: Float)

    data class Marks(val top: Mark? = null, val bottom: Mark? = null)

    fun place(marks: Marks, tapX: Float, tapY: Float): Marks {
        val top = marks.top
        val bottom = marks.bottom
        return when {
            top == null -> Marks(Mark(tapX, tapY), bottom?.copy(x = tapX))
            bottom == null -> marks.copy(bottom = Mark(top.x, tapY))
            abs(tapY - top.y) <= abs(tapY - bottom.y) -> Marks(Mark(tapX, tapY), bottom.copy(x = tapX))
            else -> marks.copy(bottom = Mark(top.x, tapY))
        }
    }
}
