package com.sempermechanics.semper.ui.analysis.load

import com.sempermechanics.semper.ui.analysis.load.BeamTapPlacement.Mark
import com.sempermechanics.semper.ui.analysis.load.BeamTapPlacement.Marks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Snap angles below are atan(|dx| / |dy|), from python: a 4 px slip over 380 px
 * is 0.60°, 37 px is 5.56°, 40 px over 360 px is 6.34°, 3 px over 360 px 0.48°,
 * 5 px over 300 px side by side 0.95°; [BeamTapPlacement.SNAP_DEGREES] is 3.
 */
class BeamTapPlacementTest {

    @Test
    fun firstTapSetsTheTopAndItsX() {
        val m = BeamTapPlacement.place(Marks(), 100f, 20f)
        assertEquals(Mark(100f, 20f), m.top)
        assertNull(m.bottom)
    }

    @Test
    fun aSecondTapNearTheVerticalLandsOnIt() {
        val m = BeamTapPlacement.place(Marks(Mark(100f, 20f)), 104f, 400f)
        assertEquals(Mark(100f, 400f), m.bottom)
        assertEquals(Mark(100f, 20f), m.top)
    }

    @Test
    fun aSecondTapOffTheVerticalKeepsItsX() {
        // A beam tilted 5.6° in the photo, tapped square to itself (TD-246).
        val m = BeamTapPlacement.place(Marks(Mark(100f, 20f)), 137f, 400f)
        assertEquals(Mark(137f, 400f), m.bottom)
        assertEquals(Mark(100f, 20f), m.top)
    }

    @Test
    fun aSecondTapNearTheHorizontalLandsOnIt() {
        // A beam upright in the photo: the report reads Eyy from a side-by-side pair.
        val m = BeamTapPlacement.place(Marks(Mark(100f, 200f)), 400f, 205f)
        assertEquals(Mark(400f, 200f), m.bottom)
    }

    @Test
    fun movingTheTopCarriesTheBottomX() {
        val placed = Marks(Mark(100f, 20f), Mark(100f, 400f))
        val m = BeamTapPlacement.place(placed, 180f, 30f)
        assertEquals(Mark(180f, 30f), m.top)
        assertEquals(Mark(180f, 400f), m.bottom)
    }

    @Test
    fun movingATiltedTopCarriesTheBottomSidewaysByAsMuch() {
        val placed = Marks(Mark(100f, 20f), Mark(137f, 400f))
        val m = BeamTapPlacement.place(placed, 110f, 25f)
        assertEquals(Mark(110f, 25f), m.top)
        assertEquals(Mark(147f, 400f), m.bottom)
    }

    @Test
    fun movingTheBottomNearTheVerticalChangesOnlyItsHeight() {
        val placed = Marks(Mark(100f, 20f), Mark(100f, 400f))
        val m = BeamTapPlacement.place(placed, 103f, 380f)
        assertEquals(Mark(100f, 20f), m.top)
        assertEquals(Mark(100f, 380f), m.bottom)
    }

    @Test
    fun movingTheBottomOffTheVerticalTiltsThePair() {
        val placed = Marks(Mark(100f, 20f), Mark(100f, 400f))
        val m = BeamTapPlacement.place(placed, 60f, 380f)
        assertEquals(Mark(100f, 20f), m.top)
        assertEquals(Mark(60f, 380f), m.bottom)
    }

    @Test
    fun theNearerMarkInHeightMoves() {
        val placed = Marks(Mark(100f, 20f), Mark(100f, 400f))
        assertEquals(Mark(100f, 20f), BeamTapPlacement.place(placed, 100f, 211f).top)
        assertEquals(Mark(100f, 209f), BeamTapPlacement.place(placed, 100f, 209f).top)
    }

    @Test
    fun onATiltedPairTheNearerMarkAlongTheTapLineMoves() {
        // Midpoint (118.5, 210); both taps are at its height, either side of it along the line.
        val placed = Marks(Mark(100f, 20f), Mark(137f, 400f))
        assertEquals(Mark(118f, 210f), BeamTapPlacement.place(placed, 118f, 210f).top)
        assertEquals(Mark(119.5f, 210f), BeamTapPlacement.place(placed, 119.5f, 210f).bottom)
    }

    @Test
    fun bottomEdgeFirstGivesTheSameEdgesSwapped() {
        // The report signs δ by the load (BeamDeflectionTest), so the order only swaps the names.
        val inOrder = BeamTapPlacement.place(BeamTapPlacement.place(Marks(), 100f, 20f), 102f, 400f)
        val bottomFirst = BeamTapPlacement.place(BeamTapPlacement.place(Marks(), 100f, 400f), 102f, 20f)
        assertEquals(inOrder.top, bottomFirst.bottom)
        assertEquals(inOrder.bottom, bottomFirst.top)
    }

    @Test
    fun aTopWithoutABottomStillAlignsALoneBottomNearItsVertical() {
        val m = BeamTapPlacement.place(Marks(bottom = Mark(98f, 400f)), 100f, 20f)
        assertEquals(Mark(100f, 400f), m.bottom)
    }

    @Test
    fun theTiltIsTheTapLinesAngleOffTheNearerAxis() {
        assertEquals(0.0, BeamTapPlacement.tiltDegrees(Mark(100f, 20f), Mark(100f, 400f)), 0.0)
        assertEquals(0.0, BeamTapPlacement.tiltDegrees(Mark(100f, 200f), Mark(400f, 200f)), 0.0)
        assertEquals(5.5613, BeamTapPlacement.tiltDegrees(Mark(100f, 20f), Mark(137f, 400f)), 1e-3)
        assertEquals(5.5613, BeamTapPlacement.tiltDegrees(Mark(20f, 100f), Mark(400f, 137f)), 1e-3)
    }
}
