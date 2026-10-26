package com.sempermechanics.semper.ui.analysis.roi

import android.content.Context
import android.text.StaticLayout
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.sempermechanics.semper.R

/**
 * The ROI studio's info pill (tvHud) above the canvas. It wraps, so its whole text
 * shows at any font scale (readiness AE1V-023), and it keeps one height whatever it
 * says: it reserves the lines its longest text needs, so the canvas below it does
 * not resize while the numbers change mid-drag or mid-pinch.
 */
internal object RoiHud {

    /** Every text the pill shows, each at its longest. */
    fun texts(context: Context): List<String> =
        listOf(
            R.string.roi_hud_select_tool,
            R.string.roi_hud_mode_erase,
            R.string.roi_hud_mode_crop,
            R.string.roi_hud_mode_manual,
            R.string.roi_hud_canvas_cleared,
            R.string.roi_hud_zoom_hint,
            R.string.roi_hud_zoom_fit,
        ).map(context::getString) +
            StudioOverlayView.RoiMode.entries.map { context.getString(R.string.roi_hud_mode_switched_fmt, it) } +
            context.getString(R.string.roi_hud_zoom_fmt, LONGEST_ZOOM) +
            context.getString(R.string.roi_hud_dimensions_fmt, LONGEST_PX, LONGEST_PX, LONGEST_PX, LONGEST_PX)

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

    /** Two digits and a decimal of zoom. */
    private const val LONGEST_ZOOM = 88.8f
}
