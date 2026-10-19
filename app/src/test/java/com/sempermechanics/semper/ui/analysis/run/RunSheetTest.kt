package com.sempermechanics.semper.ui.analysis.run

import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The wizard's run sheet: docked at the bottom, clear of the navigation bar, Cancel a text button at the end. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RunSheetTest {

    private val bed = WizardTestBed()
    private val binding get() = bed.binding
    private val density get() = bed.activity.resources.displayMetrics.density

    @After
    fun tearDown() = bed.close()

    @Test
    fun `the sheet takes the navigation bar's inset under its own padding`() {
        ComputeOverlayController(binding)
        val before = binding.runSheet.paddingBottom
        val navBar = 48
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBar))
            .build()

        ViewCompat.dispatchApplyWindowInsets(binding.computeOverlay, insets)

        assertEquals((16 * density).toInt(), before)
        assertEquals(before + navBar, binding.runSheet.paddingBottom)
    }

    @Test
    fun `the sheet is docked at the bottom over a scrim that takes every touch`() {
        val card = binding.runSheet.parent as View
        assertEquals(Gravity.BOTTOM, (card.layoutParams as FrameLayout.LayoutParams).gravity)
        assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, card.layoutParams.width)
        assertTrue(binding.computeOverlay.isClickable)
        val scrim = binding.computeOverlay.background as ColorDrawable
        assertEquals(bed.activity.getColor(R.color.run_sheet_scrim), scrim.color)
    }

    @Test
    fun `Cancel is a wrap-width button at the end, in the danger colour`() {
        val cancel: MaterialButton = binding.btnRunCancel
        assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT, cancel.layoutParams.width)
        assertEquals(Gravity.END, (cancel.layoutParams as LinearLayout.LayoutParams).gravity)
        assertEquals(bed.activity.getColor(R.color.semantic_danger), cancel.currentTextColor)
    }
}
