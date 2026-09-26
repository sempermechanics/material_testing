package com.indicvision.semper.ui.analysis

import com.indicvision.semper.ui.analysis.BeamTapPlacement.Mark
import com.indicvision.semper.ui.analysis.BeamTapPlacement.Marks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeamTapPlacementTest {

    @Test
    fun firstTapSetsTheTopAndItsX() {
        val m = BeamTapPlacement.place(Marks(), 100f, 20f)
        assertEquals(Mark(100f, 20f), m.top)
        assertNull(m.bottom)
    }

    @Test
    fun secondTapKeepsTheTopX() {
        val m = BeamTapPlacement.place(Marks(Mark(100f, 20f)), 137f, 400f)
        assertEquals(Mark(100f, 400f), m.bottom)
        assertEquals(Mark(100f, 20f), m.top)
    }

    @Test
    fun movingTheTopCarriesTheBottomX() {
        val placed = Marks(Mark(100f, 20f), Mark(100f, 400f))
        val m = BeamTapPlacement.place(placed, 180f, 30f)
        assertEquals(Mark(180f, 30f), m.top)
        assertEquals(Mark(180f, 400f), m.bottom)
    }

    @Test
    fun movingTheBottomChangesOnlyItsHeight() {
        val placed = Marks(Mark(100f, 20f), Mark(100f, 400f))
        val m = BeamTapPlacement.place(placed, 60f, 380f)
        assertEquals(Mark(100f, 20f), m.top)
        assertEquals(Mark(100f, 380f), m.bottom)
    }

    @Test
    fun theNearerMarkInHeightMoves() {
        val placed = Marks(Mark(100f, 20f), Mark(100f, 400f))
        assertEquals(Mark(100f, 20f), BeamTapPlacement.place(placed, 100f, 211f).top)
        assertEquals(Mark(100f, 209f), BeamTapPlacement.place(placed, 100f, 209f).top)
    }

    @Test
    fun bottomEdgeFirstGivesTheSameEdgesSwapped() {
        // The report signs δ by the load (BeamDeflectionTest), so the order only swaps the names.
        val inOrder = BeamTapPlacement.place(BeamTapPlacement.place(Marks(), 100f, 20f), 137f, 400f)
        val bottomFirst = BeamTapPlacement.place(BeamTapPlacement.place(Marks(), 100f, 400f), 137f, 20f)
        assertEquals(inOrder.top, bottomFirst.bottom)
        assertEquals(inOrder.bottom, bottomFirst.top)
    }

    @Test
    fun aTopWithoutABottomStillAlignsALoneBottom() {
        val m = BeamTapPlacement.place(Marks(bottom = Mark(50f, 400f)), 100f, 20f)
        assertEquals(Mark(100f, 400f), m.bottom)
    }
}
