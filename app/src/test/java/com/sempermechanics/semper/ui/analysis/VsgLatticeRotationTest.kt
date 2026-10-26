package com.sempermechanics.semper.ui.analysis

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The sweep lattice across a rotation (readiness TE1-005): the selected
 * combination and the plot mode are kept, and the line-cut profiles are not
 * decoded again from the sweep's `.dat` files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VsgLatticeRotationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var built: ActivityController<VsgLatticeActivity>? = null
    private lateinit var batchDir: File

    @After
    fun tearDown() {
        built?.close()
    }

    private fun open(): ActivityController<VsgLatticeActivity> {
        batchDir = temp.newFolder("sweep")
        val fixture = VsgLatticeFixture(batchDir).apply { writeFrames() }
        val controller = Robolectric.buildActivity(VsgLatticeActivity::class.java, fixture.intent(app))
            .also { built = it }.setup()
        awaitPlot(controller.get())
        return controller
    }

    private fun awaitPlot(lattice: VsgLatticeActivity) = idleUntil("the strain plot to load") {
        lattice.findViewById<View>(R.id.strainPlotSection).visibility == View.VISIBLE
    }

    /**
     * Deletes the sweep's `.dat` files. The decoder maps them, and Windows keeps
     * a mapped file until the mapping is collected, so collect and retry.
     */
    private fun deleteFrames() {
        repeat(DELETE_TRIES) {
            val left = batchDir.listFiles().orEmpty().filter { it.extension == "dat" && !it.delete() }
            if (left.isEmpty()) return
            System.gc()
            Thread.sleep(DELETE_PAUSE_MS)
        }
        assertEquals("the frames could not be deleted", emptyList<File>(), batchDir.listFiles().orEmpty().toList())
    }

    private fun VsgLatticeActivity.chip() = findViewById<TextView>(R.id.chipSelectedParams).text.toString()

    @Test
    fun `the selected combination and plot mode survive a rotation`() {
        val controller = open()
        controller.get().apply {
            findViewById<View>(R.id.btnNextNode).performClick()
            findViewById<View>(R.id.btnNextNode).performClick()
            findViewById<MaterialButtonToggleGroup>(R.id.rgPlotMode).check(R.id.btnPlotIsolate)
        }
        val chosen = controller.get().chip()

        val rebuilt = controller.recreate().get()
        awaitPlot(rebuilt)

        assertEquals(chosen, rebuilt.chip())
        assertEquals(
            R.id.btnPlotIsolate,
            rebuilt.findViewById<MaterialButtonToggleGroup>(R.id.rgPlotMode).checkedButtonId,
        )
        assertEquals("the last combination: no Next", false, rebuilt.findViewById<View>(R.id.btnNextNode).isEnabled)
    }

    @Test
    fun `a rotation keeps the profiles instead of decoding every frame again`() {
        val controller = open()
        // Gone from disk: a rebuilt screen that reads them again has nothing to plot.
        deleteFrames()

        val rebuilt = controller.recreate().get()

        awaitPlot(rebuilt)
    }

    private companion object {
        const val DELETE_TRIES = 50
        const val DELETE_PAUSE_MS = 20L
    }
}
