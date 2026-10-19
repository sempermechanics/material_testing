package com.sempermechanics.semper.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FieldKind] is the one field table: its labels name CSV columns and GIF /
 * PNG files, so they are pinned here byte for byte, with the data index and
 * unit each field had in the five tables it replaced.
 */
class FieldKindTest {

    @Test
    fun `labels, indices and order are the ones every export was written with`() {
        assertEquals(listOf("U", "V", "Exx", "Eyy", "Exy"), FieldKind.entries.map { it.label })
        assertEquals(
            listOf(DicResult.IDX_U, DicResult.IDX_V, DicResult.IDX_EXX, DicResult.IDX_EYY, DicResult.IDX_EXY),
            FieldKind.entries.map { it.dataIndex },
        )
    }

    @Test
    fun `report titles are the report's`() {
        assertEquals(
            listOf("U Displacement", "V Displacement", "Exx Strain", "Eyy Strain", "Exy Shear"),
            FieldKind.entries.map { it.title },
        )
    }

    @Test
    fun `strains print in millistrain, displacements in px`() {
        for (field in FieldKind.entries) {
            val strain = field.dataIndex in DicResult.IDX_EXX..DicResult.IDX_EXY
            assertEquals(field.name, strain, field.isStrain)
            assertEquals(field.name, if (strain) "mε" else "px", field.unit)
            assertEquals(field.name, DicResult.strainMultiplier(field.dataIndex), field.multiplier, 0f)
        }
        assertEquals("px", FieldKind.unitOf(DicResult.IDX_ZNSSD))
    }

    @Test
    fun `a data index finds its field, and x, y and ZNSSD have none`() {
        for (field in FieldKind.entries) assertEquals(field, FieldKind.of(field.dataIndex))
        assertNull(FieldKind.of(DicResult.IDX_X))
        assertNull(FieldKind.of(DicResult.IDX_Y))
        assertNull(FieldKind.of(DicResult.IDX_ZNSSD))
        assertTrue(FieldKind.EXX.isStrain)
        assertFalse(FieldKind.U.isStrain)
    }
}
