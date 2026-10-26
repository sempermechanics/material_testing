package com.sempermechanics.semper.ui.analysis.roi

import android.content.Context
import android.text.StaticLayout
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.sempermechanics.semper.R

/**
 * The ROI studio's info pill (tvHud) above the canvas, and the dock below it. The
 * pill wraps, so its whole text shows at any font scale (readiness AE1V-023). Each
 * edit mode sizes both for itself: the pill reserves the lines its mode's longest
 * text needs, and the dock holds only that mode's tools. The canvas between them may
 * resize on a change of mode, never within one (mid-drag or mid-pinch).
 */
internal object RoiHud {

    /** The studio's edit modes: Draw or Manual entry, each cropping or erasing. */
    enum class Mode(val manual: Boolean, val erase: Boolean) {
        DRAW_CROP(manual = false, erase = false),
        DRAW_ERASE(manual = false, erase = true),
        MANUAL_CROP(manual = true, erase = false),
        MANUAL_ERASE(manual = true, erase = true),
        ;

        companion object {
            fun of(manual: Boolean, erase: Boolean): Mode = entries.first { it.manual == manual && it.erase == erase }
        }
    }

    /**
     * Every text the pill can show in [mode], each at its longest (RoiDrawActivity's
     * tvHud writes): the selection or its prompt, a cleared canvas, the zoom (a pinch
     * works in every mode), the mode's own name, and in Draw the shape switch.
     */
    fun texts(context: Context, mode: Mode): List<String> {
        val ids = mutableListOf(
            R.string.roi_hud_canvas_cleared,
            R.string.roi_hud_zoom_fit,
            if (mode.erase) R.string.roi_hud_mode_erase else R.string.roi_hud_select_tool,
            // Crop/Erase back to Crop names Crop, in either edit mode.
            if (mode.erase) R.string.roi_hud_mode_erase else R.string.roi_hud_mode_crop,
        )
        if (mode == Mode.MANUAL_CROP) ids += R.string.roi_hud_mode_manual
        if (mode == Mode.DRAW_CROP) ids += R.string.roi_hud_zoom_hint
        val shapes = if (mode.manual) {
            emptyList()
        } else {
            StudioOverlayView.RoiMode.entries.map { context.getString(R.string.roi_hud_mode_switched_fmt, it) }
        }
        return ids.distinct().map(context::getString) + shapes +
            context.getString(R.string.roi_hud_zoom_fmt, LONGEST_ZOOM) +
            context.getString(R.string.roi_hud_dimensions_fmt, LONGEST_PX, LONGEST_PX, LONGEST_PX, LONGEST_PX)
    }

    /**
     * Shows [mode]'s tools in the dock, the other mode's gone (not kept invisible, so
     * Draw's dock is one row, not the Manual fields' height), and sizes the pill for
     * [mode]'s texts.
     */
    fun show(mode: Mode, drawTools: View, manualTools: View, hud: TextView) {
        drawTools.visibility = if (mode.manual) View.GONE else View.VISIBLE
        manualTools.visibility = if (mode.manual) View.VISIBLE else View.GONE
        reserveLines(hud, texts(hud.context, mode))
    }

    /**
     * Sets [hud]'s least line count to the most lines any of [texts] takes in the
     * width its parent leaves it. Call once [hud]'s parent is laid out.
     */
    fun reserveLines(hud: TextView, texts: List<CharSequence>) {
        val parent = hud.parent as? View ?: return
        val margins = (hud.layoutParams as? ViewGroup.MarginLayoutParams)
            ?.let { it.marginStart + it.marginEnd } ?: 0
        val width = parent.width - parent.paddingLeft - parent.paddingRight - margins -
            hud.totalPaddingLeft - hud.totalPaddingRight
        if (width <= 0) return
        val lines = texts.maxOf { text ->
            StaticLayout.Builder.obtain(text, 0, text.length, hud.paint, width)
                .setIncludePad(hud.includeFontPadding)
                .setLineSpacing(hud.lineSpacingExtra, hud.lineSpacingMultiplier)
                .build()
                .lineCount
        }
        if (hud.minLines != lines) hud.minLines = lines
    }

    /** Five-digit pixels: no photo the app takes is wider. */
    private const val LONGEST_PX = 88888

    /** Two digits and a decimal of zoom (RoiViewport.MAX_ZOOM is 10). */
    private const val LONGEST_ZOOM = 88.8f
}
