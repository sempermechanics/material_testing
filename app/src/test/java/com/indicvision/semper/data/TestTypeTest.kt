package com.indicvision.semper.data

import com.indicvision.semper.ui.analysis.VsgStudy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire names are what stored sessions carry, so they are pinned here:
 * renaming an enum constant must not orphan an `index.json` on a phone.
 */
class TestTypeTest {

    @Test
    fun `wire names are stable and round-trip`() {
        assertEquals("tensile", TestType.TENSILE.wireName)
        assertEquals("bending", TestType.BENDING.wireName)
        assertEquals(2, TestType.entries.size)
        TestType.entries.forEach { assertEquals(it, TestType.fromWire(it.wireName)) }
    }

    @Test
    fun `every test type takes machine loads`() {
        TestType.entries.forEach { assertTrue(it.name, it.hasMachineLoad) }
    }

    @Test
    fun `bending starts with a wider strain window than tensile`() {
        assertEquals(15, TestType.TENSILE.defaultStrainWindow)
        assertEquals(45, TestType.BENDING.defaultStrainWindow)
        // The slider takes odd values in its own range; a default off it would snap.
        TestType.entries.forEach {
            assertTrue(it.name, it.defaultStrainWindow % 2 == 1)
            assertTrue(it.name, it.defaultStrainWindow in VsgStudy.MIN_STRAIN_WINDOW..VsgStudy.MAX_STRAIN_WINDOW)
        }
    }

    @Test
    fun `a blank or unknown wire name is no type, not a default`() {
        assertNull(TestType.fromWire(null))
        assertNull(TestType.fromWire(""))
        assertNull(TestType.fromWire("Tensile"))
        assertNull(TestType.fromWire("shear"))
        // Types this build no longer offers read as "no type", not as another test.
        assertNull(TestType.fromWire("compression"))
        assertNull(TestType.fromWire("torsion"))
    }
}
