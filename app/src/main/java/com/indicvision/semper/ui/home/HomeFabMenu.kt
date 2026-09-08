package com.indicvision.semper.ui.home

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
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
            root.animate().alpha(0f).setDuration(COLLAPSE_DURATION_MS).withEndAction {
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
        val overlayView = buildScrim()
        val menu = buildMenuContent()

        // WRAP_CONTENT from the start — MATCH_PARENT default made measure report ~1px.
        val menuLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        overlayView.addView(menu, menuLp)
        overlayView.post { positionAndAnimate(root, menu, menuLp) }

        root.addView(overlayView)
        overlay = overlayView
    }

    private fun buildScrim(): FrameLayout =
        FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(SCRIM_COLOR)
            isClickable = true
            setOnClickListener { collapse() }
        }

    /** Fresh each call: LayoutParams belong to one view, not two. */
    private fun wrapParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun buildMenuContent(): LinearLayout {
        val menu = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { /* consume */ }
        }
        menu.addView(
            actionButton(R.string.home_fab_record, R.drawable.ic_capture) {
                collapse()
                onRecord()
            },
            wrapParams(),
        )
        menu.addView(View(activity), LinearLayout.LayoutParams(0, dp(MENU_SPACER_DP)))
        menu.addView(
            actionButton(R.string.home_fab_import, R.drawable.ic_folder) {
                collapse()
                onImport()
            },
            wrapParams(),
        )
        return menu
    }

    private fun positionAndAnimate(root: ViewGroup, menu: LinearLayout, menuLp: FrameLayout.LayoutParams) {
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val loc = IntArray(2)
        fab.getLocationInWindow(loc)
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        val w = menu.measuredWidth.coerceAtLeast(menu.width)
        val h = menu.measuredHeight.coerceAtLeast(menu.height)
        menuLp.leftMargin = (loc[0] - rootLoc[0]) + (fab.width - w) / 2
        menuLp.topMargin = (loc[1] - rootLoc[1]) - h - dp(MENU_GAP_ABOVE_FAB_DP)
        menu.layoutParams = menuLp
        menu.alpha = 0f
        menu.translationY = dp(MENU_SLIDE_DP).toFloat()
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(menu, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(menu, View.TRANSLATION_Y, dp(MENU_SLIDE_DP).toFloat(), 0f),
            )
            duration = EXPAND_DURATION_MS
            start()
        }
    }

    private fun actionButton(labelRes: Int, iconRes: Int, onClick: () -> Unit): MaterialButton =
        // Filled style + explicit surface tint — outlined + setBackgroundResource was
        // washed out by Material backgroundTint on the dim overlay.
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = activity.getString(labelRes)
            setIconResource(iconRes)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(BUTTON_ICON_PADDING_DP)
            backgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.surface))
            setTextColor(activity.getColor(R.color.text_primary))
            iconTint = ColorStateList.valueOf(activity.getColor(R.color.text_primary))
            rippleColor = ColorStateList.valueOf(activity.getColor(R.color.surface_outline))
            elevation = dp(BUTTON_ELEVATION_DP).toFloat()
            minimumWidth = dp(BUTTON_MIN_WIDTH_DP)
            minimumHeight = dp(BUTTON_MIN_HEIGHT_DP)
            setOnClickListener { onClick() }
            contentDescription = activity.getString(labelRes)
        }

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val SCRIM_COLOR = 0x66000000
        const val COLLAPSE_DURATION_MS = 120L
        const val EXPAND_DURATION_MS = 160L
        const val MENU_SPACER_DP = 8
        const val MENU_GAP_ABOVE_FAB_DP = 12
        const val MENU_SLIDE_DP = 16
        const val BUTTON_ICON_PADDING_DP = 8
        const val BUTTON_ELEVATION_DP = 6
        const val BUTTON_MIN_WIDTH_DP = 148
        const val BUTTON_MIN_HEIGHT_DP = 48
    }
}
