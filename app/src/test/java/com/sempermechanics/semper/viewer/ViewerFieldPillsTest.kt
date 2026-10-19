package com.sempermechanics.semper.viewer

import com.google.android.material.button.MaterialButton
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.FieldKind
import com.sempermechanics.semper.fixtures.viewerArgs
import com.sempermechanics.semper.fixtures.viewerController
import com.sempermechanics.semper.fixtures.writeGridBatch
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import com.sempermechanics.semper.ui.viewer.ViewerFieldPills
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The field FAB has to name the field on screen. The layout defaults to U, and
 * the field itself survives rotation in the ViewModel, so a rebuild that trusts
 * the layout comes back showing Exx with a U label.
 */
@RunWith(RobolectricTestRunner::class)
class ViewerFieldPillsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private companion object {
        const val FRAMES = 3
        const val GRID = 4
        const val STEP = 4
    }

    @Before
    fun writeBatch() {
        batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, FRAMES, GRID, STEP)
    }

    private fun ResultViewerActivity.fieldFab(): MaterialButton = findViewById(R.id.btnFieldFab)

    @Test
    fun `every field maps to its own pill`() {
        assertEquals(R.id.rbFieldU, ViewerFieldPills.idFor(FieldKind.U))
        assertEquals(R.id.rbFieldV, ViewerFieldPills.idFor(FieldKind.V))
        assertEquals(R.id.rbFieldExx, ViewerFieldPills.idFor(FieldKind.EXX))
        assertEquals(R.id.rbFieldEyy, ViewerFieldPills.idFor(FieldKind.EYY))
        assertEquals(R.id.rbFieldExy, ViewerFieldPills.idFor(FieldKind.EXY))
        assertEquals("one pill per field", FieldKind.entries.toSet(), ViewerFieldPills.BY_ID.values.toSet())
    }

    @Test
    fun `the field FAB follows the field across a rebuild`() {
        val controller = viewerController(viewerArgs(batchDir, GRID, STEP))
        val activity = controller.get()
        shadowOf(activity.mainLooper).idle()

        activity.viewModel.field = FieldKind.EXX
        shadowOf(activity.mainLooper).idle()
        assertEquals(FieldKind.EXX, activity.viewModel.field)

        val rebuilt = controller.recreate().get()
        shadowOf(rebuilt.mainLooper).idle()

        assertEquals("Exx", rebuilt.fieldFab().text.toString())
    }
}
