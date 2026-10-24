package com.sempermechanics.semper.ui.common

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Core-flow layouts at font scale 2.0 on a 360 dp phone (readiness AE1V-001,
 * AE1V-002, AE1V-003, AE1V-008, AE1V-014): text is measured with real fonts
 * (native graphics), so a value field or toggle squeezed below its text fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FontScaleLayoutTest {

    private fun context(fontScale: Float): Context {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val config = Configuration(app.resources.configuration).apply {
            this.fontScale = fontScale
            screenWidthDp = PHONE_DP
        }
        return ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_Semper)
    }

    private fun Context.px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    /** Inflates [layout] into a phone-wide column and lays it out. */
    private fun laidOut(context: Context, @LayoutRes layout: Int, merge: Boolean, widthDp: Int): View {
        val parent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val root = if (merge) {
            LayoutInflater.from(context).inflate(layout, parent, true)
            parent
        } else {
            LayoutInflater.from(context).inflate(layout, parent, false)
        }
        return root
            .also { prepare(it) }
            .apply {
                measure(
                    View.MeasureSpec.makeMeasureSpec(context.px(widthDp), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                layout(0, 0, measuredWidth, measuredHeight)
            }
    }

    private var prepare: (View) -> Unit = {}

    /** The text's own width (one line), the least a label needs to show in full. */
    private fun TextView.textWidth(): Int =
        (paint.measureText(text.toString()) + totalPaddingLeft + totalPaddingRight).toInt()

    private fun View.rightIn(ancestor: View): Int {
        var x = right
        var p = parent as? View
        while (p != null && p !== ancestor) {
            x += p.left
            p = p.parent as? View
        }
        return x
    }

    @Test
    fun `wizard parameter values keep their width at font scale 2`() {
        val ctx = context(2f)
        prepare = { root ->
            root.findViewById<EditText>(R.id.etSubsetValue).setText("121")
            root.findViewById<EditText>(R.id.etStepValue).setText("30")
            root.findViewById<EditText>(R.id.etStrainValue).setText("31")
            root.findViewById<TextView>(R.id.tvStrainVsg).text = "301 px VSG"
            root.findViewById<View>(R.id.chipSpeckle).visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.chipSpeckle).text = "Speckle 6.8 px"
        }
        // The page's 16dp side margins leave 328dp of a 360dp phone.
        val root = laidOut(ctx, R.layout.wizard_step_settings_content, merge = true, widthDp = PHONE_DP - 32)
        for (id in intArrayOf(R.id.etSubsetValue, R.id.etStepValue, R.id.etStrainValue)) {
            val field = root.findViewById<EditText>(id)
            val name = ctx.resources.getResourceEntryName(id)
            assertTrue("$name is ${field.width}px, needs ${field.textWidth()}px", field.width >= field.textWidth())
            assertTrue("$name ends at ${field.rightIn(root)} past ${root.width}", field.rightIn(root) <= root.width)
        }
    }

    @Test
    fun `the ROI studio's toggles show their whole labels at font scale 2`() {
        val ctx = context(2f)
        prepare = {}
        val root = laidOut(ctx, R.layout.activity_roi_draw, merge = false, widthDp = PHONE_DP)
        val toggles = intArrayOf(
            R.id.rbModeDraw,
            R.id.rbModeManual,
            R.id.rbCrop,
            R.id.rbErase,
            R.id.rbRect,
            R.id.rbSquare,
        )
        for (id in toggles) {
            val toggle = root.findViewById<TextView>(id)
            val name = ctx.resources.getResourceEntryName(id)
            assertTrue("$name is ${toggle.width}px, needs ${toggle.textWidth()}px", toggle.width >= toggle.textWidth())
        }
    }

    @Test
    fun `the ROI studio's Draw-Manual toggle reads in full at font scale 1`() {
        val ctx = context(1f)
        prepare = {}
        val root = laidOut(ctx, R.layout.activity_roi_draw, merge = false, widthDp = PHONE_DP)
        val manual = root.findViewById<TextView>(R.id.rbModeManual)
        assertTrue("rbModeManual is ${manual.width}px of ${manual.textWidth()}px", manual.width >= manual.textWidth())
    }

    @Test
    fun `the Google sign-in button grows with its label`() {
        val ctx = context(2f)
        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_auth, null)
        val google = root.findViewById<View>(R.id.btnGoogleSignIn)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, google.layoutParams.height)
        assertTrue(google.minimumHeight >= ctx.px(GOOGLE_MIN_DP))
    }

    @Test
    fun `licensing gates scroll`() {
        val ctx = context(2f)
        for (layout in intArrayOf(
            R.layout.activity_session_limit,
            R.layout.activity_seat_required,
            R.layout.activity_update_required,
            R.layout.activity_pending_approval,
        )) {
            val root = LayoutInflater.from(ctx).inflate(layout, null)
            assertTrue(
                "${ctx.resources.getResourceEntryName(layout)} root is ${root.javaClass.simpleName}",
                root is NestedScrollView || root is ScrollView,
            )
        }
    }

    private companion object {
        const val PHONE_DP = 360
        const val GOOGLE_MIN_DP = 52
    }
}
