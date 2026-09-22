@file:Suppress("MagicNumber")

package com.indicvision.semper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The parser's whole contract, as fixtures: each is the shape of a real UTM
 * export the app must read without asking anything.
 */
class MachineLoadCsvTest {

    private fun ok(text: String): ParsedLoadCsv = when (val r = MachineLoadCsv.parse(text)) {
        is LoadCsvParse.Ok -> r.csv
        is LoadCsvParse.Failed -> {
            fail("expected a parse, got ${r.error}")
            error("unreachable")
        }
    }

    private fun failed(text: String): LoadCsvError = when (val r = MachineLoadCsv.parse(text)) {
        is LoadCsvParse.Failed -> r.error
        is LoadCsvParse.Ok -> {
            fail("expected a failure, parsed ${r.csv}")
            error("unreachable")
        }
    }

    @Test
    fun `comma file with a header in newtons`() {
        val csv = ok(
            """
            Time (s),Extension (mm),Load (N)
            0.0,0.000,0.0
            0.5,0.012,120.5
            1.0,0.024,241.0
            """.trimIndent(),
        )

        assertEquals(listOf(0f, 120.5f, 241f), csv.loadsN)
        assertEquals(listOf(0f, 0.5f, 1f), csv.timesS)
        assertEquals(LoadUnit.N, csv.unit)
        assertEquals(2, csv.loadColumn)
        assertEquals(0, csv.timeColumn)
        assertEquals("Load (N)", csv.loadHeader)
        assertTrue(csv.warnings.isEmpty())
    }

    @Test
    fun `semicolon file with decimal commas in kilonewtons`() {
        val csv = ok(
            """
            Zeit;Kraft [kN];Weg
            0,0;0,000;0,00
            1,0;1,250;0,10
            2,0;2,500;0,20
            """.trimIndent(),
        )

        assertEquals(listOf(0f, 1250f, 2500f), csv.loadsN)
        assertEquals(LoadUnit.KN, csv.unit)
        assertEquals(1, csv.loadColumn)
        // "Zeit" is not an English time header; the column is simply not used.
        assertNull(csv.timesS)
        assertTrue(csv.warnings.isEmpty())
    }

    @Test
    fun `tab file with a separate units row`() {
        val csv = ok(
            "Time\tForce\tDisplacement\n" +
                "s\tlbf\tin\n" +
                "0\t0\t0\n" +
                "1\t100\t0.01\n",
        )

        assertEquals(2, csv.loadsN.size)
        assertEquals(LoadUnit.LBF, csv.unit)
        assertEquals(444.82217f, csv.loadsN[1], 1e-2f)
        assertEquals(listOf(0f, 1f), csv.timesS)
        assertTrue(csv.warnings.isEmpty())
    }

    @Test
    fun `header without a unit is taken as newtons and says so`() {
        val csv = ok("Load\n5\n10\n")

        assertEquals(listOf(5f, 10f), csv.loadsN)
        assertEquals(LoadUnit.N, csv.unit)
        assertEquals(listOf(LoadCsvWarning.UNITS_ASSUMED_N), csv.warnings)
    }

    @Test
    fun `headerless two columns picks the increasing one as time and the last as load`() {
        val csv = ok(
            """
            0.0,0
            0.1,-50
            0.2,-100
            """.trimIndent(),
        )

        assertEquals(listOf(0f, -50f, -100f), csv.loadsN)
        assertEquals(listOf(0f, 0.1f, 0.2f), csv.timesS)
        assertEquals(1, csv.loadColumn)
        assertEquals(0, csv.timeColumn)
        assertTrue(LoadCsvWarning.COLUMN_GUESSED in csv.warnings)
        assertTrue(LoadCsvWarning.UNITS_ASSUMED_N in csv.warnings)
    }

    @Test
    fun `headerless single column is the load`() {
        val csv = ok("0\n12.5\n25\n")

        assertEquals(listOf(0f, 12.5f, 25f), csv.loadsN)
        assertNull(csv.timesS)
        assertEquals(0, csv.loadColumn)
    }

    @Test
    fun `byte order mark, blank lines and comments are ignored`() {
        val csv = ok("﻿# exported 2026-09-21\n\nLoad (N)\n\n1\n2\n\n")

        assertEquals(listOf(1f, 2f), csv.loadsN)
    }

    @Test
    fun `thousands separators in a semicolon file are stripped`() {
        // A comma with a decimal point after it can only be a thousands mark;
        // a bare "1,250" in a semicolon file would be read as 1.25 instead.
        val csv = ok("Load (N);Ext\n\"1,250.0\";0\n\"12,500.5\";1\n")

        assertEquals(listOf(1250f, 12500.5f), csv.loadsN)
    }

    @Test
    fun `a non-numeric load cell fails and names the line`() {
        val error = failed("Load (N)\n1\n2\nOVERLOAD\n4\n")

        assertEquals(LoadCsvError.NonNumeric(line = 4, column = 1, text = "OVERLOAD"), error)
    }

    @Test
    fun `a short row fails as a blank load cell`() {
        val error = failed("Time (s),Load (N)\n0,1\n1\n")

        assertEquals(LoadCsvError.NonNumeric(line = 3, column = 2, text = ""), error)
    }

    @Test
    fun `a file with no rows is empty`() {
        assertEquals(LoadCsvError.Empty, failed(""))
        assertEquals(LoadCsvError.Empty, failed("# just a comment\n"))
        assertEquals(LoadCsvError.Empty, failed("Time,Load (N)\n"))
    }

    @Test
    fun `a header with no load column and text rows has no load`() {
        assertEquals(LoadCsvError.NoLoadColumn, failed("Name,Notes\nfoo,bar\nbaz,qux\n"))
    }

    @Test
    fun `a time column that goes backwards is dropped with a warning`() {
        val csv = ok("Time (s),Load (N)\n0,1\n2,2\n1,3\n")

        assertNull(csv.timesS)
        assertNull(csv.timeColumn)
        assertEquals(listOf(1f, 2f, 3f), csv.loadsN)
        assertTrue(LoadCsvWarning.TIME_IGNORED in csv.warnings)
    }

    @Test
    fun `too many rows is refused rather than read`() {
        val text = buildString {
            append("Load (N)\n")
            repeat(MachineLoadCsv.MAX_ROWS + 1) { append(it).append('\n') }
        }

        assertEquals(LoadCsvError.TooManyRows, failed(text))
    }

    @Test
    fun `windows line endings and quoted cells parse the same`() {
        val csv = ok("\"Time (s)\",\"Load (N)\"\r\n\"0\",\"0\"\r\n\"1\",\"9.5\"\r\n")

        assertEquals(listOf(0f, 9.5f), csv.loadsN)
        assertEquals(listOf(0f, 1f), csv.timesS)
    }
}
