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
        for (id in intArrayOf(R.id.tvSubsetLabel, R.id.tvStrainLabel)) {
            val label = root.findViewById<TextView>(id)
            val name = ctx.resources.getResourceEntryName(id)
            assertTrue("$name starts ${label.top}px above its row", label.top >= 0)
            val text = label.text
            val midWord = (0 until label.layout.lineCount - 1).map { label.layout.getLineEnd(it) }
                .filter { end -> !text[end - 1].isWhitespace() }
            assertEquals("$name breaks mid-word at $midWord", emptyList<Int>(), midWord)
        }
        // A value keeps a gap before its unit ("31 px", not "31px").
        val units = listOf(R.id.etSubsetValue to R.id.tvSubsetUnit, R.id.etStrainValue to R.id.tvStrainUnit)
        for ((value, unit) in units) {
            val gap = root.findViewById<View>(unit).left - root.findViewById<View>(value).right
            val name = ctx.resources.getResourceEntryName(unit)
            assertTrue("$name is ${gap}px from its value", gap >= ctx.px(UNIT_GAP_DP))
        }
    }

    @Test
    fun `the wizard's Compute button shows its whole label at font scale 2`() {
        val ctx = context(2f)
        prepare = { root ->
            // The parameters page's bar: Back and Compute.
            root.findViewById<View>(R.id.btnBack).visibility = View.VISIBLE
            root.findViewById<View>(R.id.btnNext).visibility = View.GONE
            root.findViewById<TextView>(R.id.btnCalculateFullField).apply {
                visibility = View.VISIBLE
                text = ctx.getString(R.string.run_compute_eta_fmt, ctx.getString(R.string.run_estimate_minutes_fmt, 12))
            }
        }
        val root = laidOut(ctx, R.layout.activity_static_analysis, merge = false, widthDp = PHONE_DP)
        val compute = root.findViewById<TextView>(R.id.btnCalculateFullField)
        val room = compute.width - compute.totalPaddingLeft - compute.totalPaddingRight
        val lines = compute.layout.lineCount
        assertTrue(
            "Compute label '${compute.text}' needs $lines lines of ${compute.maxLines}",
            lines <= compute.maxLines,
        )
        val midWord = (0 until lines - 1).map { compute.layout.getLineEnd(it) }
            .filter { end -> !compute.text[end - 1].isWhitespace() }
        assertEquals("Compute label breaks mid-word at $midWord", emptyList<Int>(), midWord)
        val widest = (0 until lines).maxOf { compute.layout.getLineWidth(it) }
        assertTrue("Compute label line is ${widest}px in ${room}px", widest <= room)
        val textHeight = compute.layout.height + compute.totalPaddingTop + compute.totalPaddingBottom
        assertTrue("Compute is ${compute.height}px high, its text ${textHeight}px", compute.height >= textHeight)
        val back = root.findViewById<View>(R.id.btnBack)
        assertEquals("Back and Compute heights", compute.height, back.height)
    }

    @Test
    fun `the sweep lattice's plot title and buttons show in full at font scale 2`() {
        val ctx = context(2f)
        prepare = { root ->
            root.findViewById<View>(R.id.strainPlotSection).visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.tvStrainPlotTitle).text =
                ctx.getString(R.string.line_cut_title_axis_fmt, "X")
        }
        val root = laidOut(ctx, R.layout.activity_vsg_lattice, merge = false, widthDp = PHONE_DP)
        for (id in intArrayOf(R.id.tvStrainPlotTitle, R.id.btnSaveGraph, R.id.btnView)) {
            val view = root.findViewById<TextView>(id)
            val name = ctx.resources.getResourceEntryName(id)
            val lines = view.layout.lineCount
            assertTrue("$name '${view.text}' needs $lines lines of ${view.maxLines}", lines <= view.maxLines)
            assertEquals("$name ellipsized", 0, view.layout.getEllipsisCount(lines - 1))
            val textHeight = view.layout.height + view.totalPaddingTop + view.totalPaddingBottom
            assertTrue("$name is ${view.height}px high, its text ${textHeight}px", view.height >= textHeight)
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
            // On screen, not scrolled off the edge (the after-screenshot read "Eras").
            assertTrue("$name ends at ${toggle.rightIn(root)} past ${root.width}", toggle.rightIn(root) <= root.width)
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
    fun `the ROI studio keeps Use full image left and Crop-Erase right when they share a row`() {
        val ctx = context(1f)
        prepare = {}
        val root = laidOut(ctx, R.layout.activity_roi_draw, merge = false, widthDp = PHONE_DP)
        val chip = root.findViewById<View>(R.id.btnFullImageRoi)
        val group = root.findViewById<View>(R.id.rgCropErase)
        val chipLeft = chip.rightIn(root) - chip.width
        val chipMid = (chip.top + chip.bottom) / 2
        assertTrue("one row at font scale 1", chipMid in group.top..group.bottom)
        assertTrue("Use full image starts at ${chipLeft}px", chipLeft <= ctx.px(EDGE_DP))
        val groupRight = group.rightIn(root)
        assertTrue("Crop/Erase ends at ${groupRight}px of ${root.width}", groupRight >= root.width - ctx.px(EDGE_DP))
    }

    @Test
    fun `the ROI studio keeps Use full image left and Crop-Erase right when they wrap`() {
        val ctx = context(2f)
        prepare = {}
        val root = laidOut(ctx, R.layout.activity_roi_draw, merge = false, widthDp = PHONE_DP)
        val chip = root.findViewById<View>(R.id.btnFullImageRoi)
        val group = root.findViewById<View>(R.id.rgCropErase)
        assertTrue("two rows at font scale 2", group.top >= chip.bottom)
        val chipLeft = chip.rightIn(root) - chip.width
        assertTrue("Use full image starts at ${chipLeft}px", chipLeft <= ctx.px(EDGE_DP))
        val groupRight = group.rightIn(root)
        assertTrue("Crop/Erase ends at ${groupRight}px of ${root.width}", groupRight >= root.width - ctx.px(EDGE_DP))
    }

    @Test
    fun `the deformed frames' range names both files in full at font scale 1`() = assertRangeInFull(1f)

    @Test
    fun `the deformed frames' range names both files in full at font scale 2`() = assertRangeInFull(2f)

    private fun assertRangeInFull(fontScale: Float) {
        val ctx = context(fontScale)
        prepare = { root ->
            root.findViewById<View>(R.id.defCard).visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.tvDefName).text = "4 deformed frames"
            // A phone camera's names: on the 360 dp emulator even "specimen_01.png …
            // specimen_04.png" lost a letter on one line.
            root.findViewById<TextView>(R.id.tvDefMeta).text = "IMG_20261024_101530.png … IMG_20261024_101934.png"
        }
        val root = laidOut(ctx, R.layout.activity_static_analysis, merge = false, widthDp = PHONE_DP)
        val meta = root.findViewById<TextView>(R.id.tvDefMeta)
        val lines = meta.layout.lineCount
        val cut = (0 until lines).sumOf { meta.layout.getEllipsisCount(it) }
        assertEquals("tvDefMeta '${meta.text}' ellipsizes $cut chars", 0, cut)
    }

    @Test
    fun `a frame's order badge holds its number at font scale 2`() {
        val ctx = context(2f)
        prepare = { it.findViewById<TextView>(R.id.tvFrameBadge).text = "50" }
        val root = laidOut(ctx, R.layout.item_frame_order, merge = true, widthDp = TILE_DP)
        val badge = root.findViewById<TextView>(R.id.tvFrameBadge)
        val line = badge.paint.fontMetricsInt.let { it.descent - it.ascent }
        assertTrue("tvFrameBadge is ${badge.width}px, needs ${badge.textWidth()}px", badge.width >= badge.textWidth())
        assertTrue("tvFrameBadge is ${badge.height}px high, its line ${line}px", badge.height >= line)
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

    /**
     * The ROI studio's info pill (tvHud) sits above the canvas, never on it: at font
     * scale 2 its two lines covered the crop's top edge and corner handles (readiness
     * AE1V-023). It keeps to one line, so the canvas does not resize while the numbers
     * change mid-drag, and its whole text stays the view's text for TalkBack.
     */
    @Test
    fun `the ROI studio's info pill never covers the canvas at font scale 2`() {
        val ctx = context(2f)
        val full = ctx.getString(R.string.roi_hud_dimensions_fmt, 6000, 4000, 1234, 5678)
        prepare = { it.findViewById<TextView>(R.id.tvHud).text = full }
        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_roi_draw, LinearLayout(ctx), false).apply {
            prepare(this)
            measure(
                View.MeasureSpec.makeMeasureSpec(ctx.px(PHONE_DP), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(ctx.px(PHONE_HEIGHT_DP), View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
        val hud = root.findViewById<TextView>(R.id.tvHud)
        val canvas = root.findViewById<View>(R.id.canvasFrame)
        assertTrue("the pill ends at ${hud.bottom}px, the canvas starts at ${canvas.top}px", hud.bottom <= canvas.top)
        assertTrue("the canvas keeps room to draw (${canvas.height}px)", canvas.height > 0)
        assertEquals("one line", 1, hud.lineCount)
        assertEquals("TalkBack reads it whole", full, hud.text.toString())
    }

    private companion object {
        const val PHONE_DP = 360
        const val PHONE_HEIGHT_DP = 640
        const val GOOGLE_MIN_DP = 52
        const val TILE_DP = 72
        const val UNIT_GAP_DP = 4

        /** The ROI tool row's side padding (12dp) plus a little slack. */
        const val EDGE_DP = 16
    }
}
