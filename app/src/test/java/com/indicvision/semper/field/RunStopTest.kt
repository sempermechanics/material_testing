package com.indicvision.semper.field

import com.indicvision.semper.ui.analysis.run.AnalysisRunCodes
import com.indicvision.semper.ui.analysis.run.EngineFailure
import com.indicvision.semper.ui.analysis.sweep.VsgStudyRunner
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RunStop] is the existing `Int` stop codes, losslessly. */
class RunStopTest {

    @Test
    fun `named cases carry the codes the app already uses`() {
        assertEquals(0, RunStop.Finished.wireCode)
        assertEquals(EngineFailure.ENGINE_ERROR_FEATURES, RunStop.FeaturesUnmatched.wireCode)
        assertEquals(EngineFailure.ENGINE_ERROR_ROI, RunStop.InvalidRoi.wireCode)
        assertEquals(EngineFailure.ENGINE_ERROR_INIT, RunStop.InitFailed.wireCode)
        assertEquals(AnalysisRunCodes.ERROR_LOW_CONVERGENCE, RunStop.LowConvergence.wireCode)
        assertEquals(VsgStudyRunner.ERROR_ENGINE_FAILED, RunStop.SweepEngineFailed.wireCode)
        assertEquals(AnalysisRunCodes.ERROR_SESSION_LIMIT, RunStop.SessionLimit.wireCode)
        assertEquals(AnalysisViewModel.ERROR_SESSION_LIMIT, RunStop.SessionLimit.wireCode)
        assertEquals(AnalysisRunCodes.ERROR_CANCELLED, RunStop.Cancelled.wireCode)
        assertEquals(AnalysisViewModel.ERROR_CANCELLED, RunStop.Cancelled.wireCode)
        assertEquals(VsgStudyRunner.ERROR_CANCELLED, RunStop.Cancelled.wireCode)
    }

    @Test
    fun `every named case round-trips and the codes are distinct`() {
        for (stop in RunStop.named) {
            assertEquals(stop, RunStop.fromWireCode(stop.wireCode))
        }
        assertEquals(RunStop.named.size, RunStop.named.map { it.wireCode }.toSet().size)
    }

    @Test
    fun `the named list is exhaustive over the sealed cases`() {
        // A new case fails to compile here until it is handled, and then fails
        // the size check until it is added to RunStop.named.
        val handled = RunStop.named.count { stop ->
            when (stop) {
                RunStop.Finished,
                RunStop.FeaturesUnmatched,
                RunStop.InvalidRoi,
                RunStop.InitFailed,
                RunStop.LowConvergence,
                RunStop.SweepEngineFailed,
                RunStop.SessionLimit,
                RunStop.Cancelled,
                -> true
                is RunStop.Other -> false
            }
        }
        assertEquals(8, handled)
    }

    @Test
    fun `every int round-trips through fromWireCode`() {
        val codes = (-300..300) + listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, Int.MAX_VALUE, -1000, 1000)
        for (code in codes) {
            assertEquals(code, RunStop.fromWireCode(code).wireCode)
        }
    }

    @Test
    fun `an unnamed code is Other and keeps its value`() {
        assertEquals(RunStop.Other(-4), RunStop.fromWireCode(-4))
        assertEquals(RunStop.Other(7), RunStop.fromWireCode(7))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Other refuses a named code so equality stays unambiguous`() {
        RunStop.Other(-99)
    }

    @Test
    fun `stoppedEarly is the record's stopCode != 0`() {
        assertFalse(RunStop.Finished.stoppedEarly)
        for (code in listOf(-1, -2, -3, -96, -97, -98, -99, -4, 5)) {
            assertTrue("$code", RunStop.fromWireCode(code).stoppedEarly)
        }
    }
}
