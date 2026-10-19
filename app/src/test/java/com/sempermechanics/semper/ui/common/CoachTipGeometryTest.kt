package com.sempermechanics.semper.ui.common

import com.sempermechanics.semper.ui.common.CoachTipGeometry.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the tip card and its pointer go: next to the target on the side with
 * room, inside the safe area (clear of the system bars), the pointer aimed at
 * the target's centre but never past the card's rounded corners.
 */
class CoachTipGeometryTest {

    private val m = CoachTipGeometry.Metrics(
        margin = 16,
        maxWidth = 400,
        gap = 12,
        pointerWidth = 16,
        pointerHeight = 8,
        pointerInset = 12,
    )

    /** A 360 x 800 phone with a 24 px status bar and a 48 px navigation bar. */
    private val safe = Box(0, 24, 360, 752)

    private val width get() = CoachTipGeometry.cardWidth(safe, m)

    @Test
    fun `the card spans the safe area less its margins, up to a cap`() {
        assertEquals(328, width)
        assertEquals(400, CoachTipGeometry.cardWidth(Box(0, 0, 1200, 800), m))
    }

    @Test
    fun `a card that fits below the target goes below, its pointer pointing up at the centre`() {
        val target = Box(100, 100, 200, 160)

        val p = CoachTipGeometry.place(target, safe, width, 120, m)

        assertTrue(p.pointsUp)
        assertTrue(p.showPointer)
        assertEquals(target.bottom + m.gap, p.card.top)
        assertEquals(p.card.top - m.pointerHeight, p.pointerTop)
        assertEquals(target.centerX - m.pointerWidth / 2, p.pointerLeft)
    }

    @Test
    fun `a target near the bottom gets the card above it, pointing down`() {
        val target = Box(100, 650, 200, 710)

        val p = CoachTipGeometry.place(target, safe, width, 120, m)

        assertFalse(p.pointsUp)
        assertTrue(p.showPointer)
        assertEquals(target.top - m.gap, p.card.bottom)
        assertEquals(p.card.bottom, p.pointerTop)
    }

    @Test
    fun `the card stays inside the safe area and its margins`() {
        val nearEdge = Box(0, 30, 40, 60)

        val p = CoachTipGeometry.place(nearEdge, safe, width, 120, m)

        assertEquals(safe.left + m.margin, p.card.left)
        assertTrue(p.card.right <= safe.right - m.margin)
        assertTrue(p.card.top >= safe.top + m.margin)
        assertTrue(p.card.bottom <= safe.bottom - m.margin)
    }

    @Test
    fun `the pointer stops short of the card's corners for a target at the edge`() {
        val nearEdge = Box(0, 100, 20, 140)

        val p = CoachTipGeometry.place(nearEdge, safe, width, 120, m)

        assertEquals(p.card.left + m.pointerInset, p.pointerLeft)
    }

    @Test
    fun `with no room either side the card overlaps the target and drops the pointer`() {
        val tall = Box(20, 40, 340, 740)

        val p = CoachTipGeometry.place(tall, safe, width, 200, m)

        assertFalse(p.showPointer)
        assertTrue(p.card.top >= safe.top + m.margin)
        assertTrue(p.card.bottom <= safe.bottom - m.margin)
    }

    @Test
    fun `a card taller than the safe area starts at its top`() {
        val p = CoachTipGeometry.place(Box(100, 100, 200, 160), safe, width, 2000, m)

        assertEquals(safe.top + m.margin, p.card.top)
    }
}
