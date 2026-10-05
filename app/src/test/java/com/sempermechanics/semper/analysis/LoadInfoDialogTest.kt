package com.indicvision.semper.analysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import com.indicvision.semper.data.LoadCsvParse
import com.indicvision.semper.data.LoadUnit
import com.indicvision.semper.data.MachineLoadCsv
import com.indicvision.semper.data.TestType
import com.indicvision.semper.ui.analysis.LoadInfoDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The load card's ⓘ shows a sample of the CSV a machine must export. The
 * sample is the help's promise, so it must read through [MachineLoadCsv]
 * exactly as the text says: header found, load and time columns named, the
 * unit read from the header, and nothing guessed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoadInfoDialogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun parseSample(type: TestType) =
        MachineLoadCsv.parse(context.getString(LoadInfoDialog.textsFor(type).csvSample)) as LoadCsvParse.Ok

    @Test
    fun `the tensile sample parses in kN with a time column and no guesses`() {
        val csv = parseSample(TestType.TENSILE).csv
        assertEquals(LoadUnit.KN, csv.unit)
        assertEquals(1, csv.loadColumn)
        assertEquals(0, csv.timeColumn)
        assertTrue(csv.warnings.toString(), csv.warnings.isEmpty())
        assertEquals(1250f, csv.loadsN[1], 0.01f)
    }

    @Test
    fun `the bending sample parses in N with a time column and no guesses`() {
        val csv = parseSample(TestType.BENDING).csv
        assertEquals(LoadUnit.N, csv.unit)
        assertEquals(0, csv.timeColumn)
        assertTrue(csv.warnings.toString(), csv.warnings.isEmpty())
        // One 0.5 kg hanger step: 0.5 × 9.81 N.
        assertEquals(4.9f, csv.loadsN[1], 0.01f)
    }

    @Test
    fun `each test gets its own sample and diagram text`() {
        val tensile = LoadInfoDialog.textsFor(TestType.TENSILE)
        val bending = LoadInfoDialog.textsFor(TestType.BENDING)
        assertNotEquals(tensile.csvSample, bending.csvSample)
        assertNotEquals(tensile.diagramBody, bending.diagramBody)
        assertEquals(R.string.info_load_diagram_heading_bending, bending.diagramHeading)
    }
}
