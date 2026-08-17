package com.indicvision.semper.ui.common

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.indicvision.semper.R

/**
 * Compact wrap-content pill. Material Snackbars stretch full-width and read as
 * banners; this sits bottom-center and removes itself.
 */
object CrispToast {

    fun show(context: Context, message: CharSequence, long: Boolean = false) {
        val activity = context as? Activity
        if (activity == null || activity.isFinishing) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val existing = root.findViewWithTag<android.view.View>(TAG)
        if (existing != null) root.removeView(existing)
        val pill = activity.layoutInflater.inflate(R.layout.toast_crisp, root, false)
        pill.tag = TAG
        pill.findViewById<TextView>(R.id.tvToast).text = message
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            val m = (12 * activity.resources.displayMetrics.density).toInt()
            val bottom = (72 * activity.resources.displayMetrics.density).toInt()
            setMargins(m, m, m, bottom)
        }
        root.addView(pill, lp)
        val duration = if (long) LONG_MS else SHORT_MS
        pill.postDelayed({
            val parent = pill.parent as? ViewGroup
            parent?.removeView(pill)
        }, duration)
    }

    private const val TAG = "semper_crisp_toast"
    private const val SHORT_MS = 2000L
    private const val LONG_MS = 3500L
}
