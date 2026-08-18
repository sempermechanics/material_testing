package com.indicvision.semper.viewer

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.indicvision.semper.DicKeys
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.ui.viewer.ResultViewerActivity
import com.indicvision.semper.ui.viewer.ViewerFieldPills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The lit pill has to name the field on screen. The layout checks U, and the
 * field itself survives rotation in the ViewModel, so a rebuild that trusts the
 * layout comes back showing Exx with the U pill lit.
 */
// Pinned like the other Robolectric tests: 4.14 tops out below our targetSdk.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
        for (f in 0 until FRAMES) {
            val points = GRID * GRID
            val buffer = ByteBuffer.allocate(points * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
            for (i in 0 until points) {
                buffer.putFloat(((i % GRID) * STEP).toFloat())
                buffer.putFloat(((i / GRID) * STEP).toFloat())
                buffer.putFloat(f.toFloat())
                buffer.putFloat(0f)
                buffer.putFloat(f * 0.001f)
                buffer.putFloat(0f)
                buffer.putFloat(0f)
                buffer.putFloat(0.01f)
            }
            File(batchDir, "frame_%03d.dat".format(f)).writeBytes(buffer.array())
        }
    }

    private fun intent(): Intent =
        Intent(ApplicationProvider.getApplicationContext(), ResultViewerActivity::class.java)
            .putExtra(DicKeys.IMG_W, GRID * STEP)
            .putExtra(DicKeys.IMG_H, GRID * STEP)
            .putExtra(DicKeys.STEP, STEP)
            .putExtra(DicKeys.BATCH_DIR_PATH, batchDir.absolutePath)
            .putExtra(DicKeys.START_FRAME, 0)

    private fun ResultViewerActivity.pill(id: Int): MaterialButton = findViewById(id)

    @Test
    fun `every field index maps to its own pill`() {
        assertEquals(R.id.rbFieldU, ViewerFieldPills.idFor(DicResult.IDX_U))
        assertEquals(R.id.rbFieldV, ViewerFieldPills.idFor(DicResult.IDX_V))
        assertEquals(R.id.rbFieldExx, ViewerFieldPills.idFor(DicResult.IDX_EXX))
        assertEquals(R.id.rbFieldEyy, ViewerFieldPills.idFor(DicResult.IDX_EYY))
        assertEquals(R.id.rbFieldExy, ViewerFieldPills.idFor(DicResult.IDX_EXY))
    }

    @Test
    fun `an index off the map falls back to U rather than lighting nothing`() {
        assertEquals(R.id.rbFieldU, ViewerFieldPills.idFor(-1))
    }

    @Test
    fun `the pill follows the field across a rebuild`() {
        val controller = Robolectric.buildActivity(ResultViewerActivity::class.java, intent()).setup()
        val activity = controller.get()
        shadowOf(activity.mainLooper).idle()

        activity.pill(R.id.rbFieldExx).performClick()
        shadowOf(activity.mainLooper).idle()
        assertEquals(DicResult.IDX_EXX, activity.currentDataIndex)

        val rebuilt = controller.recreate().get()
        shadowOf(rebuilt.mainLooper).idle()

        assertTrue(rebuilt.pill(R.id.rbFieldExx).isChecked)
        assertFalse(rebuilt.pill(R.id.rbFieldU).isChecked)
    }
}
