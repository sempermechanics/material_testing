package com.indicvision.semper.report

import org.junit.Assert.assertEquals
import org.junit.Test

class LabReportFormatTest {

    @Test
    fun `a metal's modulus keeps one decimal`() {
        assertEquals("153.6", LabReportFormat.gpa(153.64f))
        assertEquals("10.0", LabReportFormat.gpa(10f))
    }

    @Test
    fun `a polymer's two estimates stay apart`() {
        // PMMA's graph and mean E once read "2.0" and "2.1" GPa.
        assertEquals("2.00", LabReportFormat.gpa(2.004f))
        assertEquals("2.09", LabReportFormat.gpa(2.093f))
        assertEquals("-0.52", LabReportFormat.gpa(-0.52f))
    }

    @Test
    fun `the frame cell spans a held load`() {
        assertEquals("7", LabReportFormat.frameCell(6))
        assertEquals("5–9", LabReportFormat.frameCell(4, 8))
    }
}
