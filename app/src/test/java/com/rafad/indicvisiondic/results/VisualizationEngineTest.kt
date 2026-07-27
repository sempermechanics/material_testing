package com.rafad.indicvisiondic.results

import com.rafad.indicvisiondic.report.VisualizationEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class VisualizationEngineTest {

    @Test
    fun `DISPLAY_MAX_EDGE equals 1080`() {
        assertEquals(1080, VisualizationEngine.DISPLAY_MAX_EDGE)
    }
}
