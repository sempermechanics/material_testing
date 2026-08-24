package com.indicvision.semper.ui.home

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R

/**
 * Expands the Home + button into Import / Record mini-actions.
 * Keeps the expansion logic out of [HomeActivity].
 */
class HomeFabMenu(
    private val activity: AppCompatActivity,
    private val fab: ImageButton,
    private val onImport: () -> Unit,
    private val onRecord: () -> Unit,
) {
    private var expanded = false
    private var overlay: FrameLayout? = null
    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = collapse()
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
    }

    fun toggle() {
        if (expanded) collapse() else expand()
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        backCallback.isEnabled = false
        overlay?.let { root ->
            root.animate().alpha(0f).setDuration(120L).withEndAction {
                (root.parent as? ViewGroup)?.removeView(root)
            }.start()
        }
        overlay = null
    }

    fun isExpanded(): Boolean = expanded

    private fun expand() {
        if (expanded) return
        expanded = true
        backCallback.isEnabled = true

        val root = activity.findViewById<ViewGroup>(R.id.homeRoot)
        val overlayView = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0x66000000)
            isClickable = true
            setOnClickListener { collapse() }
        }

        val menu = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { /* consume */ }
        }

        menu.addView(actionButton(R.string.home_fab_record, R.drawable.ic_capture) {
            collapse()
            onRecord()
        })
        menu.addView(
            spacer(8),
        )
        menu.addView(actionButton(R.string.home_fab_import, R.drawable.ic_folder) {
            collapse()
            onImport()
        })

        // Position the menu just above the FAB.
        overlayView.addView(menu)
        overlayView.post {
            val loc = IntArray(2)
            fab.getLocationInWindow(loc)
            val rootLoc = IntArray(2)
            root.getLocationInWindow(rootLoc)
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.gravity = Gravity.TOP or Gravity.START
            menu.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            lp.leftMargin = (loc[0] - rootLoc[0]) + (fab.width - menu.measuredWidth) / 2
            lp.topMargin = (loc[1] - rootLoc[1]) - menu.measuredHeight - dp(12)
            menu.layoutParams = lp
            menu.alpha = 0f
            menu.translationY = dp(16).toFloat()
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(menu, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(menu, View.TRANSLATION_Y, dp(16).toFloat(), 0f),
                )
                duration = 160L
                start()
            }
        }

        root.addView(overlayView)
        overlay = overlayView
    }

    private fun actionButton(labelRes: Int, iconRes: Int, onClick: () -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(18), dp(10))
            setBackgroundResource(R.drawable.bg_fab_menu_item)
            elevation = dp(4).toFloat()
            setOnClickListener { onClick() }
        }
        val icon = ImageButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setImageResource(iconRes)
            background = null
            isClickable = false
            contentDescription = null
        }
        val label = TextView(activity).apply {
            text = activity.getString(labelRes)
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 15f
            setPadding(dp(10), 0, 0, 0)
        }
        // Prefer MaterialButton look for accessibility, but keep compact row.
        row.addView(icon)
        row.addView(label)
        row.contentDescription = activity.getString(labelRes)
        return row
    }

    private fun spacer(dpVal: Int): View =
        View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(dpVal))
        }

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()
}
