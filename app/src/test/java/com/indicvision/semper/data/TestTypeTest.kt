package com.indicvision.semper.data

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
