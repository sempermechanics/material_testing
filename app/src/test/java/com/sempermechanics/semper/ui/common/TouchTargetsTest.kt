package com.sempermechanics.semper.ui.common

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.LinearLayout
import androidx.annotation.LayoutRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import com.sempermechanics.semper.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every tappable icon, pill, chip, check box, spinner, inline value field, text
 * link and header row on the core flows is at least 48 x 48 dp (readiness
 * AE1-006, AE1-102). Sizes are read from the inflated views' layout params and
 * minimums, so a view that is gone at first (an info button on a hidden section)
 * is still checked. A chip may reach 48 dp through ensureMinTouchTargetSize.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TouchTargetsTest {

    private val themed = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(),
        R.style.Theme_Semper,
    )
    private val minPx = (MIN_DP * themed.resources.displayMetrics.density).toInt()

    private fun parent() = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }

    private fun inflate(@LayoutRes layout: Int): View = LayoutInflater.from(themed).inflate(layout, parent(), false)

    /** A `<merge>` layout only inflates into a parent. */
    private fun inflateMerge(@LayoutRes layout: Int): View = parent().also {
        LayoutInflater.from(themed).inflate(layout, it, true)
    }

    private fun View.walk(): Sequence<View> = sequence {
        yield(this@walk)
        if (this@walk is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).walk())
    }

    private fun View.isTarget(): Boolean = when (this) {
        is Chip -> isClickable || isCheckable
        is ImageButton, is MaterialButton, is CompoundButton, is Spinner -> true
        // A typed value: the wizard's inline numbers were 24 dp tall (readiness AE1-102).
        // A field in a TextInputLayout takes the box's height, so it is not checked here.
        is EditText -> isEnabled && !inTextInputLayout()
        // A slider pads its own touch area to 48 dp.
        is Slider, is RangeSlider -> false
        // Selectable text (an e-mail address, a device id) is not a button.
        is TextView -> !isTextSelectable && (isClickable || isButtonId())
        // Text links and rows made clickable in the layout or named as buttons.
        else -> isClickable || isButtonId()
    }

    private fun View.isButtonId(): Boolean = id != View.NO_ID && name(this).startsWith("btn")

    private fun View.inTextInputLayout(): Boolean {
        var p = parent
        while (p is View) {
            if (p is TextInputLayout) return true
            p = p.parent
        }
        return false
    }

    /** The side's size: a fixed layout size, else the view's own minimum. */
    private fun side(param: Int, minimum: Int): Int = if (param > 0) param else minimum

    private fun tooSmall(root: View): List<String> = root.walk().filter { it.isTarget() }.mapNotNull { view ->
        val lp = view.layoutParams
        val h = side(lp?.height ?: 0, view.minimumHeight)
        val w = lp?.width ?: 0
        val chipOk = view is Chip && (view.shouldEnsureMinTouchTargetSize() || view.chipMinHeight >= minPx)
        val shortSide = when {
            chipOk -> null
            w in 1 until minPx -> "w=${w / themed.resources.displayMetrics.density}dp"
            lp?.height != ViewGroup.LayoutParams.MATCH_PARENT && h < minPx ->
                "h=${h / themed.resources.displayMetrics.density}dp"
            else -> null
        }
        shortSide?.let { "${name(view)} $it" }
    }.toList()

    private fun name(view: View): String =
        if (view.id == View.NO_ID) view.javaClass.simpleName else themed.resources.getResourceEntryName(view.id)

    @Test
    fun `layouts keep tap targets at 48dp`() {
        val roots = listOf(
            "activity_auth" to inflate(R.layout.activity_auth),
            "activity_home" to inflate(R.layout.activity_home),
            "activity_result_viewer" to inflate(R.layout.activity_result_viewer),
            "activity_roi_draw" to inflate(R.layout.activity_roi_draw),
            "activity_settings" to inflate(R.layout.activity_settings),
            "activity_static_analysis" to inflate(R.layout.activity_static_analysis),
            "activity_vsg_lattice" to inflate(R.layout.activity_vsg_lattice),
            "item_analysis_data" to inflate(R.layout.item_analysis_data),
            "item_session" to inflate(R.layout.item_session),
            "popup_field_options" to inflate(R.layout.popup_field_options),
            "sheet_video_sampling" to inflate(R.layout.sheet_video_sampling),
            "view_settings_scroll_content" to inflateMerge(R.layout.view_settings_scroll_content),
            "view_transfer_banner" to inflate(R.layout.view_transfer_banner),
            "view_warn_chip" to inflate(R.layout.view_warn_chip),
            "wizard_step_settings_content" to inflateMerge(R.layout.wizard_step_settings_content),
            "wizard_step_sweep" to inflate(R.layout.wizard_step_sweep),
        )
        val small = roots.flatMap { (layout, root) -> tooSmall(root).map { "$layout: $it" } }
        assertEquals(emptyList<String>(), small)
    }

    @Test
    fun `a settings section header is a 48dp row`() {
        // Readiness AE1-102: the seven headers were 32 dp tall (4 dp vertical padding).
        val header = inflate(R.layout.view_settings_section_header)
        assertTrue("header minHeight ${header.minimumHeight}", header.minimumHeight >= minPx)
    }

    private companion object {
        const val MIN_DP = 48
    }
}
