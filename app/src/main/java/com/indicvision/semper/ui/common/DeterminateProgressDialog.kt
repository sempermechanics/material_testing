// Small literal layout/percent constants (100% max, 24dp padding) read clearest
// inline for this one-off dialog view.
@file:Suppress("MagicNumber")

package com.indicvision.semper.ui.common

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * A small non-cancelable determinate progress dialog: a status line over a
 * horizontal bar. Replaces the old indeterminate "generating…" message dialog so
 * long export/share jobs show real "X of N" / percentage motion.
 *
 * Build it on the main thread; [update] hops to the main thread itself, so
 * generators running on a background dispatcher can call it directly.
 */
class DeterminateProgressDialog(private val activity: AppCompatActivity, title: CharSequence) {

    private val label = TextView(activity).apply {
        text = title
    }

    private val bar = LinearProgressIndicator(activity).apply {
        isIndeterminate = true
        max = 100
    }

    private val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle(title)
        .setView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = (activity.resources.displayMetrics.density * 24).toInt()
                setPadding(pad, pad, pad, pad)
                addView(label)
                addView(
                    bar,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = pad / 2 },
                )
            },
        )
        .setCancelable(false)
        .create()

    fun show() {
        dialog.show()
    }

    /** Set determinate progress. Safe to call from any thread. */
    fun update(percent: Int, text: CharSequence? = null) {
        activity.runOnUiThread {
            bar.isIndeterminate = false
            bar.setProgressCompat(percent.coerceIn(0, 100), true)
            if (text != null) label.text = text
        }
    }

    fun dismiss() {
        dialog.dismiss()
    }
}
