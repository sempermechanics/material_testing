package com.sempermechanics.semper.ui.common

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.annotation.LayoutRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.sempermechanics.semper.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TouchTargetsTest's 48 x 48 dp rule for the layouts only this app has (the lab
 * load card and Results panel, examples, licences, beam edges; readiness
 * MT-AE1-001). Several are behind a ViewStub, which TouchTargetsTest's hosts
 * do not inflate, so each is inflated on its own here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LabTouchTargetsTest {

    private val themed = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(),
        R.style.Theme_Semper,
    )
    private val density = themed.resources.displayMetrics.density
    private val minPx = (MIN_DP * density).toInt()

    private fun inflate(@LayoutRes layout: Int): View = LayoutInflater.from(themed)
        .inflate(layout, LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }, false)

    private fun View.walk(): Sequence<View> = sequence {
        yield(this@walk)
        if (this@walk is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).walk())
    }

    private fun View.isTarget(): Boolean = when (this) {
        is Chip -> isClickable || isCheckable
        is ImageButton, is MaterialButton -> true
        else -> false
    }

    private fun tooSmall(root: View): List<String> = root.walk().filter { it.isTarget() }.mapNotNull { view ->
        val lp = view.layoutParams
        val h = if ((lp?.height ?: 0) > 0) lp.height else view.minimumHeight
        val w = lp?.width ?: 0
        val chipOk = view is Chip && (view.shouldEnsureMinTouchTargetSize() || view.chipMinHeight >= minPx)
        val shortSide = when {
            chipOk -> null
            w in 1 until minPx -> "w=${w / density}dp"
            lp?.height != ViewGroup.LayoutParams.MATCH_PARENT && h < minPx -> "h=${h / density}dp"
            else -> null
        }
        shortSide?.let { "${name(view)} $it" }
    }.toList()

    private fun name(view: View): String =
        if (view.id == View.NO_ID) view.javaClass.simpleName else themed.resources.getResourceEntryName(view.id)

    @Test
    fun `lab layouts keep tap targets at 48dp`() {
        val roots = listOf(
            "activity_beam_edge_tap" to R.layout.activity_beam_edge_tap,
            "activity_examples" to R.layout.activity_examples,
            "activity_licences" to R.layout.activity_licences,
            "item_example" to R.layout.item_example,
            "lab_results_panel" to R.layout.lab_results_panel,
            "row_load_point" to R.layout.row_load_point,
            "sheet_example_detail" to R.layout.sheet_example_detail,
            "sheet_test_type" to R.layout.sheet_test_type,
            "sheet_typed_loads" to R.layout.sheet_typed_loads,
            "wizard_load_card" to R.layout.wizard_load_card,
        )
        val small = roots.flatMap { (name, layout) -> tooSmall(inflate(layout)).map { "$name: $it" } }
        assertEquals(emptyList<String>(), small)
    }

    private companion object {
        const val MIN_DP = 48
    }
}
