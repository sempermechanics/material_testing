package com.rafad.indicvisiondic.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Edge-to-edge window-insets helpers.
 *
 * On targetSdk 35+ (Android 15/16) apps are ALWAYS edge-to-edge — content
 * draws under the status bar and navigation bar, and the strips behind them
 * are system gesture zones. Without handling this, top toolbars sit under
 * the clock and in the swipe-down-for-notifications area (so taps get eaten
 * by the system), and bottom controls hide under the nav bar / gesture pill.
 *
 * These helpers add the relevant system-bar inset as PADDING (preserving any
 * padding already set in XML), so backgrounds still extend edge-to-edge while
 * the interactive content is pushed into the safe, reachable area.
 */
object Insets {

    /** Pad the top by the status-bar inset (e.g. an app bar). */
    fun padTop(view: View) = applyInsets(view, top = true)

    /** Pad the bottom by the navigation-bar inset (e.g. a bottom action row). */
    fun padBottom(view: View) = applyInsets(view, bottom = true)

    /** Pad both top and bottom (e.g. a full-screen root with its own bars). */
    fun padVertical(view: View) = applyInsets(view, top = true, bottom = true)

    private fun applyInsets(view: View, top: Boolean = false, bottom: Boolean = false) {
        // Capture the padding declared in XML so we ADD the inset to it
        // rather than overwrite it (idempotent across re-dispatches).
        val startTop = view.paddingTop
        val startBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                top = if (top) startTop + bars.top else v.paddingTop,
                bottom = if (bottom) startBottom + bars.bottom else v.paddingBottom
            )
            windowInsets
        }
        // Ensure the listener runs even if the view is already attached.
        if (ViewCompat.isAttachedToWindow(view)) ViewCompat.requestApplyInsets(view)
    }
}
