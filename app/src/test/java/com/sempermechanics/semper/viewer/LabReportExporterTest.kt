package com.indicvision.semper.viewer

import com.indicvision.semper.ui.viewer.LabReportExporter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabReportExporterTest {

    @Test
    fun `tensile with loads gets the lab report`() {
        assertTrue(LabReportExporter.offered("tensile", hasLoads = true, isSweep = false, hasLoadPoint = false))
    }

    @Test
    fun `bending gets it only once the thickness is tapped`() {
        assertFalse(LabReportExporter.offered("bending", hasLoads = true, isSweep = false, hasLoadPoint = false))
        assertTrue(LabReportExporter.offered("bending", hasLoads = true, isSweep = false, hasLoadPoint = true))
    }

    @Test
    fun `no loads, a sweep or an untyped session gets none`() {
        assertFalse(LabReportExporter.offered("tensile", hasLoads = false, isSweep = false, hasLoadPoint = false))
        assertFalse(LabReportExporter.offered("tensile", hasLoads = true, isSweep = true, hasLoadPoint = false))
        assertFalse(LabReportExporter.offered("", hasLoads = true, isSweep = false, hasLoadPoint = true))
    }
}
