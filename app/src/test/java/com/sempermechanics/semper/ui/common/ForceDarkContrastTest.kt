package com.sempermechanics.semper.ui.common

import android.app.Application
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.color.MaterialColors
import com.sempermechanics.semper.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.min

/**
 * A control's boundary reaches 3:1 (WCAG 1.4.11) in the light theme and in the dark
 * one (readiness AE1-004). Dark is force dark: at draw time HWUI recolours the light
 * palette (frameworks/base/libs/hwui/CanvasTransform.cpp). A background — a view's
 * background drawable, or a layout drawing children — is made dark, L* becoming
 * min(110 - L*, 100) when that is darker; content — text, an icon, a check box's box —
 * is made light the same way when that is lighter. A dark stroke therefore stays dark
 * on a darkened page, which is how an outline that passes by day vanishes at night.
 * Robolectric does not model force dark, so the transform is applied here, to the colour
 * tokens the app draws with; each test runs by day and at night.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForceDarkContrastTest {

    private val themed = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(),
        R.style.Theme_Semper,
    )

    private val layouts = File("src/main/res/layout")

    private val night: Boolean
        get() = themed.resources.configuration.isNightModeActive

    private fun c(@ColorRes id: Int) = ContextCompat.getColor(themed, id)

    /** [color] as force dark draws it: [darken] for a background, else as content. */
    private fun forced(@ColorInt color: Int, darken: Boolean): Int {
        if (!night) return color
        val lab = DoubleArray(3)
        ColorUtils.colorToLAB(color, lab)
        val inverted = min(110.0 - lab[0], 100.0)
        val moves = if (darken) inverted < lab[0] else inverted > lab[0]
        if (!moves) return color
        return ColorUtils.setAlphaComponent(ColorUtils.LABToColor(inverted, lab[1], lab[2]), Color.alpha(color))
    }

    private fun background(@ColorInt color: Int) = forced(color, darken = true)

    private fun content(@ColorInt color: Int) = forced(color, darken = false)

    /** Composites [layers] bottom-up; the first layer must be opaque. */
    private fun stack(vararg layers: Int): Int = layers.reduce { bg, fg -> ColorUtils.compositeColors(fg, bg) }

    private val failures = mutableListOf<String>()

    private fun boundary(label: String, fg: Int, bg: Int) {
        val ratio = ColorUtils.calculateContrast(ColorUtils.compositeColors(fg, bg), bg)
        if (ratio < NON_TEXT) {
            failures += "%s %s is %.2f:1, needs 3:1".format(if (night) "night" else "day", label, ratio)
        }
    }

    /** The light pages a control sits on, as drawn in the current theme. */
    private fun pages() = listOf(
        "surface" to background(c(R.color.surface)),
        "surface_muted" to background(c(R.color.surface_muted)),
    )

    /** Every opening TextInputLayout tag in the app's layouts, with its file's name. */
    private fun inputTags(): List<Pair<String, String>> = layouts.listFiles().orEmpty().sortedBy { it.name }
        .flatMap { file ->
            INPUT_TAG.findAll(file.readText()).map { file.nameWithoutExtension to it.value }.toList()
        }

    /**
     * The resting stroke of an outlined field, as TextInputLayout picks it: a stateful
     * boxStrokeColor gives its default colour; a single colour is only the focused
     * stroke and leaves MDC's default; none leaves the style's.
     */
    private fun restingStroke(tag: String): Int {
        val res = themed.resources
        val attr = STROKE_ATTR.find(tag)?.groupValues?.get(1)
        if (attr != null) {
            val list = requireNotNull(
                ContextCompat.getColorStateList(themed, res.getIdentifier(attr, "color", themed.packageName)),
            )
            return if (list.isStateful) {
                list.defaultColor
            } else {
                c(com.google.android.material.R.color.mtrl_textinput_default_box_stroke_color)
            }
        }
        val style = requireNotNull(STYLE_ATTR.find(tag)).groupValues[1]
        val ta = themed.obtainStyledAttributes(
            res.getIdentifier(style, "style", themed.packageName),
            intArrayOf(com.google.android.material.R.attr.boxStrokeColor),
        )
        val list = requireNotNull(ta.getColorStateList(0))
        ta.recycle()
        return list.defaultColor
    }

    private fun checkedControls() {
        // MaterialCheckBox, SwitchMaterial's thumb and RadioButton fill with
        // colorControlActivated (?attr/colorAccent, which MDC points at colorSecondary).
        for ((name, attr) in listOf(
            "colorControlActivated" to androidx.appcompat.R.attr.colorControlActivated,
            "colorSecondary" to com.google.android.material.R.attr.colorSecondary,
        )) {
            val fill = content(MaterialColors.getColor(themed, attr, name))
            for ((page, bg) in pages()) boundary("checked $name on $page", fill, bg)
        }
    }

    private fun inputOutlines() {
        val tags = inputTags()
        check(tags.size >= 15) { "found ${tags.size} TextInputLayouts; is the layout path right?" }
        for ((layout, tag) in tags) {
            val id = ID_ATTR.find(tag)?.groupValues?.get(1) ?: "?"
            // The outline is the field's background drawable.
            val stroke = background(restingStroke(tag))
            for ((page, bg) in pages()) boundary("$layout/$id resting outline on $page", stroke, bg)
        }
    }

    private fun roiSegmentedOutline() {
        val ta = themed.obtainStyledAttributes(
            R.style.Widget_Semper_SegmentedButton_Dark,
            intArrayOf(com.google.android.material.R.attr.strokeColor),
        )
        val stroke = background(ta.getColor(0, 0))
        ta.recycle()
        // The ROI studio's header panel (bg_viewer_panel) over the screen's canvas.
        val panel = stack(background(c(R.color.viewer_canvas)), background(c(R.color.viewer_glass)))
        boundary("Widget.Semper.SegmentedButton.Dark stroke on the ROI header", stroke, panel)
    }

    private fun allChecks(): List<String> {
        checkedControls()
        inputOutlines()
        roiSegmentedOutline()
        return failures
    }

    @Test
    fun `control boundaries reach 3 to 1 by day`() {
        assertEquals(emptyList<String>(), allChecks())
    }

    @Test
    @Config(qualifiers = "night")
    fun `control boundaries reach 3 to 1 at night, after force dark`() {
        check(night)
        assertEquals(emptyList<String>(), allChecks())
    }

    /**
     * The viewer's previous / next pucks (bg_chrome_ghost) sit on the page, viewerRoot's
     * surface_muted, which force dark darkens. Their night tokens are already tuned for
     * a dark page (a white rim), so the buttons opt out of force dark as the top bar's
     * do; darkened a second time the rim went dark too, about 1.0:1 (readiness AE1V-021).
     */
    @Test
    @Config(qualifiers = "night")
    fun `the viewer's frame buttons keep a visible ring at night`() {
        check(night)
        val xml = layouts.resolve("activity_result_viewer.xml").readText()
        val page = background(c(R.color.surface_muted))
        for (id in listOf("btnPrevFrame", "btnNextFrame")) {
            val tag = requireNotNull(Regex("""<ImageButton\b[^>]*android:id="@\+id/$id"[^>]*>""").find(xml)) {
                "$id is not an ImageButton in activity_result_viewer"
            }.value
            check("android:background=\"@drawable/bg_chrome_ghost\"" in tag) { "$id no longer draws bg_chrome_ghost" }
            val optedOut = "android:forceDarkAllowed=\"false\"" in tag
            val drawn = { color: Int -> if (optedOut) color else background(color) }
            val puck = stack(page, drawn(c(R.color.viewer_glass_fill)))
            boundary("$id ring", stack(puck, drawn(c(R.color.viewer_glass_stroke))), page)
        }
        assertEquals(emptyList<String>(), failures)
    }

    private companion object {
        const val NON_TEXT = 3.0
        val INPUT_TAG = Regex("""<com\.google\.android\.material\.textfield\.TextInputLayout\b[^>]*>""")
        val STROKE_ATTR = Regex("""app:boxStrokeColor="@color/(\w+)"""")
        val STYLE_ATTR = Regex("""style="@style/([\w.]+)"""")
        val ID_ATTR = Regex("""android:id="@\+id/(\w+)"""")
    }
}
