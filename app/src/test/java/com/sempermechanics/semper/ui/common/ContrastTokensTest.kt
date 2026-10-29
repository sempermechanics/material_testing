package com.sempermechanics.semper.ui.common

import android.app.Application
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.report.VisualizationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WCAG 2.x contrast of the colour tokens as the app uses them (readiness AE1-001..005,
 * AE1V-012): text needs 4.5:1, a control's boundary or a meaningful icon 3:1. A token
 * with alpha is composited over the layers it sits on, bottom first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContrastTokensTest {

    private val themed = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(),
        R.style.Theme_Semper,
    )

    private fun c(@ColorRes id: Int) = ContextCompat.getColor(themed, id)

    /** Composites [layers] bottom-up; the first layer must be opaque. */
    private fun stack(vararg layers: Int): Int = layers.reduce { bg, fg -> ColorUtils.compositeColors(fg, bg) }

    private fun assertContrast(label: String, fg: Int, bg: Int, need: Double) {
        val ratio = ColorUtils.calculateContrast(ColorUtils.compositeColors(fg, bg), bg)
        assertTrue("$label is %.2f:1, needs %.1f:1".format(ratio, need), ratio >= need)
    }

    private fun assertText(label: String, fg: Int, vararg bg: Int) = assertContrast(label, fg, stack(*bg), TEXT)

    private fun assertBoundary(label: String, fg: Int, vararg bg: Int) = assertContrast(label, fg, stack(*bg), NON_TEXT)

    @Test
    fun `brand blue reads as text and carries white button labels`() {
        assertText("sky_primary on surface", c(R.color.sky_primary), c(R.color.surface))
        assertText("sky_primary on surface_muted", c(R.color.sky_primary), c(R.color.surface_muted))
        assertText("sky_primary on sky_container", c(R.color.sky_primary), c(R.color.sky_container))
        assertText("white on the primary button", c(R.color.text_on_primary), c(R.color.sky_primary))
        val button = MaterialButton(themed)
        assertText(
            "primary button label",
            button.textColors.defaultColor,
            requireNotNull(button.backgroundTintList).defaultColor,
        )
    }

    @Test
    fun `warning text and icons use the warning ink`() {
        val ink = c(R.color.semantic_warning_ink)
        assertText("warning ink on surface", ink, c(R.color.surface))
        assertText("warning ink on surface_muted", ink, c(R.color.surface_muted))
        assertText("warning ink on the warn chip", ink, c(R.color.surface), c(R.color.warn_chip_fill))
        assertText("warning ink on the warn chip", ink, c(R.color.surface_muted), c(R.color.warn_chip_fill))
        val chip = LayoutInflater.from(themed).inflate(R.layout.view_warn_chip, FrameLayout(themed), false)
        assertText(
            "view_warn_chip text",
            chip.findViewById<TextView>(R.id.tvWarnText).currentTextColor,
            c(R.color.surface_muted),
            c(R.color.warn_chip_fill),
        )
    }

    @Test
    fun `danger text passes on both backgrounds`() {
        assertText("semantic_danger on surface", c(R.color.semantic_danger), c(R.color.surface))
        assertText("semantic_danger on surface_muted", c(R.color.semantic_danger), c(R.color.surface_muted))
    }

    @Test
    fun `the Device ID and Terms version captions are readable`() {
        for ((layout, id) in listOf(
            R.layout.activity_pending_approval to R.id.tvDeviceId,
            R.layout.activity_terms to R.id.tvTermsVersion,
        )) {
            val root = LayoutInflater.from(themed).inflate(layout, FrameLayout(themed), false)
            val caption = root.findViewById<TextView>(id)
            assertText(themed.resources.getResourceEntryName(id), caption.currentTextColor, c(R.color.surface_muted))
        }
    }

    @Test
    fun `the colour scale's end labels read over every heatmap colour`() {
        val root = LayoutInflater.from(themed).inflate(R.layout.activity_result_viewer, FrameLayout(themed), false)
        for (id in intArrayOf(R.id.tvScaleMax, R.id.tvScaleMin)) {
            val label = root.findViewById<TextView>(id)
            val chip = (label.background as? GradientDrawable)?.color?.defaultColor
            for (field in VisualizationEngine.rampColors()) {
                val bg = if (chip == null) field else stack(field, chip)
                assertText(
                    "${themed.resources.getResourceEntryName(id)} over #%06X".format(field and 0xFFFFFF),
                    label.currentTextColor,
                    bg,
                )
            }
        }
    }

    @Test
    fun `control boundaries reach 3 to 1`() {
        val strong = c(R.color.outline_strong)
        assertBoundary("outline_strong on surface", strong, c(R.color.surface))
        assertBoundary("outline_strong on surface_muted", strong, c(R.color.surface_muted))
        val inputStroke = requireNotNull(ContextCompat.getColorStateList(themed, R.color.input_box_stroke))
        assertBoundary("unfocused input stroke", inputStroke.defaultColor, c(R.color.surface))
        val slider = Slider(themed)
        assertBoundary("slider inactive track", slider.trackInactiveTintList.defaultColor, c(R.color.surface))
        assertBoundary("semantic_success", c(R.color.semantic_success), c(R.color.surface))
        assertBoundary("viewer_plot_muted", c(R.color.viewer_plot_muted), c(R.color.surface_muted))
        assertBoundary("warning icon", c(R.color.semantic_warning_ink), c(R.color.surface))
    }

    /** The style or colour an attribute of [context]'s theme points at. */
    private fun themeRes(context: android.content.Context, attr: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        val id = ta.getResourceId(0, 0)
        ta.recycle()
        return id
    }

    @Test
    fun `alert dialog body text reads on the dialog`() {
        // Readiness AE1-105: Material's medium-emphasis body was 4.25:1 on white.
        val overlay = themeRes(themed, com.google.android.material.R.attr.materialAlertDialogTheme)
        val dialog = ContextThemeWrapper(themed, overlay)
        val bodyStyle = themeRes(dialog, com.google.android.material.R.attr.materialAlertDialogBodyTextStyle)
        val ta = dialog.obtainStyledAttributes(bodyStyle, intArrayOf(android.R.attr.textColor))
        val body = requireNotNull(ta.getColorStateList(0)).defaultColor
        ta.recycle()
        assertText("dialog body on surface", body, c(R.color.surface))
    }

    @Test
    fun `an off switch's track reaches 3 to 1 on the page`() {
        // Readiness AE1-106: the off track was 1.89:1 and the thumb 1.02:1 on surface_muted.
        val style = themeRes(themed, androidx.appcompat.R.attr.switchStyle)
        val ta = themed.obtainStyledAttributes(style, intArrayOf(androidx.appcompat.R.attr.trackTint))
        val track = requireNotNull(ta.getColorStateList(0)) { "switchStyle sets no trackTint" }
        ta.recycle()
        val off = track.getColorForState(intArrayOf(android.R.attr.state_enabled), track.defaultColor)
        assertBoundary("off switch track on surface_muted", off, c(R.color.surface_muted))
        assertBoundary("off switch track on surface", off, c(R.color.surface))
    }

    @Test
    @Config(qualifiers = "night")
    fun `night field puck label reads over any image`() {
        // Readiness AE1-114: the white label over the 22% frost fell to 2.4:1 over a bright heatmap.
        val pill = requireNotNull(ContextCompat.getColorStateList(themed, R.color.field_pill_bg)).defaultColor
        val ink = requireNotNull(ContextCompat.getColorStateList(themed, R.color.field_pill_text)).defaultColor
        for ((name, image) in listOf("white" to 0xFFFFFFFF.toInt(), "mid grey" to 0xFF898989.toInt())) {
            assertText("puck label over $name", ink, image, pill)
        }
        assertText("puck label over the canvas", ink, c(R.color.viewer_canvas), pill)
    }

    /** The fill [themed]'s default bottom sheets draw (bottomSheetDialogTheme, then bottomSheetStyle). */
    private fun sheetFill(): Int {
        val overlay = themeRes(themed, com.google.android.material.R.attr.bottomSheetDialogTheme)
        val sheetTheme = ContextThemeWrapper(themed, overlay)
        val style = themeRes(sheetTheme, com.google.android.material.R.attr.bottomSheetStyle)
        val ta = sheetTheme.obtainStyledAttributes(style, intArrayOf(com.google.android.material.R.attr.backgroundTint))
        val fill = requireNotNull(ta.getColorStateList(0)) { "the sheet style sets no backgroundTint" }.defaultColor
        ta.recycle()
        return fill
    }

    @Test
    @Config(qualifiers = "night")
    fun `night sheets are dark, so their status bar draws light icons`() {
        // Readiness AE1-113: Material picks the sheet's status-bar icons from its fill's
        // luminance; a white fill (force dark inverts it only on screen) gave black
        // icons on the dark bar, 1.22:1.
        assertTrue("night sheet fill is light", ColorUtils.calculateLuminance(sheetFill()) < 0.5)
    }

    @Test
    fun `day sheets stay white`() {
        assertEquals(c(R.color.surface), sheetFill())
    }

    @Test
    fun `the segmented style's stroke is the strong outline`() {
        val ta = themed.obtainStyledAttributes(
            R.style.Widget_Semper_SegmentedButton,
            intArrayOf(com.google.android.material.R.attr.strokeColor),
        )
        val stroke = ta.getColor(0, 0)
        ta.recycle()
        assertBoundary("Widget.Semper.SegmentedButton strokeColor", stroke, c(R.color.surface))
    }

    @Test
    @Config(qualifiers = "night")
    fun `night viewer chrome muted labels read on the glass puck`() {
        assertText(
            "viewer_chrome_muted on glass",
            c(R.color.viewer_chrome_muted),
            c(R.color.viewer_canvas),
            c(R.color.viewer_glass_fill),
        )
        assertText(
            "viewer_chrome_text on glass",
            c(R.color.viewer_chrome_text),
            c(R.color.viewer_canvas),
            c(R.color.viewer_glass_fill),
        )
    }

    /**
     * The sweep lattice opts out of force dark, so its outlined Save graph button sits
     * on the night canvas as written; the darker brand blue was 3.5:1 there.
     */
    @Test
    @Config(qualifiers = "night")
    fun `the lattice's outlined button reads on the night canvas`() {
        val root = LayoutInflater.from(themed).inflate(R.layout.activity_vsg_lattice, FrameLayout(themed), false)
        val save = root.findViewById<MaterialButton>(R.id.btnSaveGraph)
        assertText("btnSaveGraph label", save.currentTextColor, c(R.color.viewer_canvas))
        assertBoundary("btnSaveGraph stroke", requireNotNull(save.strokeColor).defaultColor, c(R.color.viewer_canvas))
    }

    @Test
    fun `the lattice's outlined button reads on the day canvas`() {
        val root = LayoutInflater.from(themed).inflate(R.layout.activity_vsg_lattice, FrameLayout(themed), false)
        val save = root.findViewById<MaterialButton>(R.id.btnSaveGraph)
        assertText("btnSaveGraph label", save.currentTextColor, c(R.color.viewer_canvas))
        assertBoundary("btnSaveGraph stroke", requireNotNull(save.strokeColor).defaultColor, c(R.color.viewer_canvas))
    }

    private companion object {
        const val TEXT = 4.5
        const val NON_TEXT = 3.0
    }
}
