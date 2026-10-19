package com.sempermechanics.semper.ui.analysis.load

import com.sempermechanics.semper.ui.analysis.BeamEdgeTapActivity
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.tan

/**
 * Where a tap lands in [BeamEdgeTapActivity]. The first tap is the top edge
 * and sets the load point; the second is the bottom edge, straight across the
 * beam from it. The thickness is the distance between them, along that line,
 * and the report reads the beam's axis square to it (`BeamDeflection.Probe`),
 * so a beam tilted in the photo is tapped square to itself and measured so.
 *
 * A second tap within [SNAP_DEGREES] of the top mark's vertical line lands on
 * it (and within as much of its horizontal one, on that): a level beam's pair
 * stays exactly one above the other whatever the finger's sideways slip, and
 * reads Exx and the vertical thickness exactly as before the tilt was allowed.
 * Snapping a beam that is truly tilted by less than that costs under 0.15 % of
 * the scale and 0.3 % of the strain, below a one-pixel slip on a 300 px beam.
 *
 * Once both are placed, a tap moves the mark nearer along the tap line: the
 * top moves to it and carries the bottom sideways by as much (so a pair one
 * above the other stays so), the bottom moves to it; either then snaps again.
 *
 * "Top" is the first tap, wherever it lands: tapping the bottom edge first
 * gives the same thickness and midpoint with the two swapped, and the report
 * signs δ by the load, not by this order.
 */
internal object BeamTapPlacement {

    /** How far off an image axis, in degrees, a pair is still put on it. */
    const val SNAP_DEGREES = 3.0

    private val snapTan = tan(Math.toRadians(SNAP_DEGREES)).toFloat()

    data class Mark(val x: Float, val y: Float)

    data class Marks(val top: Mark? = null, val bottom: Mark? = null)

    fun place(marks: Marks, tapX: Float, tapY: Float): Marks {
        val top = marks.top
        val bottom = marks.bottom
        val tap = Mark(tapX, tapY)
        return when {
            top == null -> Marks(tap, bottom?.let { snap(tap, it) })
            bottom == null -> marks.copy(bottom = snap(top, tap))
            nearerTop(top, bottom, tap) -> Marks(tap, snap(tap, bottom.copy(x = tapX + (bottom.x - top.x))))
            else -> marks.copy(bottom = snap(top, tap))
        }
    }

    /**
     * [bottom], put on [top]'s vertical line when it is within [SNAP_DEGREES]
     * of it, or on its horizontal line likewise; else as it is.
     */
    fun snap(top: Mark, bottom: Mark): Mark {
        val dx = abs(bottom.x - top.x)
        val dy = abs(bottom.y - top.y)
        return when {
            dx <= dy * snapTan -> bottom.copy(x = top.x)
            dy <= dx * snapTan -> bottom.copy(y = top.y)
            else -> bottom
        }
    }

    /** Whether [tap] is nearer [top] than [bottom] along the line between them. */
    private fun nearerTop(top: Mark, bottom: Mark, tap: Mark): Boolean {
        val lineX = bottom.x - top.x
        val lineY = bottom.y - top.y
        val toTop = (tap.x - top.x) * lineX + (tap.y - top.y) * lineY
        val toBottom = (tap.x - bottom.x) * lineX + (tap.y - bottom.y) * lineY
        return abs(toTop) <= abs(toBottom)
    }

    /**
     * The tap line's angle off the nearer image axis, in degrees (0 to 45);
     * 0 for a pair on an axis, which the report reads as Exx or Eyy.
     */
    fun tiltDegrees(top: Mark, bottom: Mark): Double {
        val dx = abs(bottom.x - top.x).toDouble()
        val dy = abs(bottom.y - top.y).toDouble()
        return Math.toDegrees(atan2(minOf(dx, dy), maxOf(dx, dy)))
    }
}
