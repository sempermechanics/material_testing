@file:Suppress("MagicNumber")

package com.indicvision.semper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedLoadsTest {

    @Test
    fun `kilograms become newtons at standard gravity`() {
        val table = TypedLoads.toTable(listOf(0.5f, 1f, 1.5f), frameCount = 3)!!

        assertEquals(0.5f * 9.80665f, table.loadsN[0], 1e-5f)
        assertEquals(9.80665f, table.loadsN[1], 1e-5f)
        assertEquals(14.709975f, table.loadsN[2], 1e-4f)
        assertEquals(LoadMapping.TYPED_KG, table.mapping)
        assertTrue(table.warnings.isEmpty())
        assertEquals(3, table.matchedFrames)
    }

    @Test
    fun `a blank box leaves that frame without a load`() {
        val table = TypedLoads.toTable(listOf(0.5f, null, 1.5f), frameCount = 3)!!

        assertTrue(table.loadsN[1].isNaN())
        assertNull(table.loadsN.loadOfFrame(1))
        assertEquals(2, table.matchedFrames)
        assertEquals(2, table.sourceRows)
    }

    @Test
    fun `nothing typed is no table`() {
        assertNull(TypedLoads.toTable(listOf(null, null), frameCount = 2))
        assertNull(TypedLoads.toTable(emptyList(), frameCount = 2))
        assertNull(TypedLoads.toTable(listOf(1f), frameCount = 0))
    }

    @Test
    fun `the table always has one load per frame`() {
        // Short: the missing frames have no load. Long: the extra values are not frames.
        assertEquals(3, TypedLoads.toTable(listOf(1f), frameCount = 3)!!.loadsN.size)
        assertEquals(1, TypedLoads.toTable(listOf(1f, 2f, 3f), frameCount = 1)!!.loadsN.size)
    }

    @Test
    fun `a zero-kilogram frame is a load of zero, not a blank`() {
        val table = TypedLoads.toTable(listOf(0f), frameCount = 1)!!

        assertEquals(0f, table.loadsN[0], 0f)
        assertEquals(1, table.matchedFrames)
    }

    @Test
    fun `boxes parse from the keypad`() {
        assertEquals(TypedLoads.Parsed.Kg(1.5f), TypedLoads.parseKg("1.5"))
        assertEquals(TypedLoads.Parsed.Kg(1.5f), TypedLoads.parseKg(" 1,5 "))
        assertEquals(TypedLoads.Parsed.Kg(2f), TypedLoads.parseKg("2"))
        assertEquals(TypedLoads.Parsed.Kg(0.5f), TypedLoads.parseKg(".5"))
        assertEquals(TypedLoads.Parsed.Blank, TypedLoads.parseKg(""))
        assertEquals(TypedLoads.Parsed.Blank, TypedLoads.parseKg("  "))
    }

    @Test
    fun `a negative, non-numeric or infinite box is invalid`() {
        assertEquals(TypedLoads.Parsed.Invalid, TypedLoads.parseKg("-1"))
        assertEquals(TypedLoads.Parsed.Invalid, TypedLoads.parseKg("1.2.3"))
        assertEquals(TypedLoads.Parsed.Invalid, TypedLoads.parseKg("abc"))
        assertEquals(TypedLoads.Parsed.Invalid, TypedLoads.parseKg("Infinity"))
        assertEquals(TypedLoads.Parsed.Invalid, TypedLoads.parseKg("NaN"))
    }
}
