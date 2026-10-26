// The overlay is placed in code, so its dp sizes, insets and scrim alpha are
// literal by nature and read clearest inline — MagicNumber is suppressed for
// this file.
@file:Suppress("MagicNumber")

package com.sempermechanics.semper.ui.common

import android.app.Activity
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.CoachPrefs
import com.sempermechanics.semper.databinding.ViewCoachTipBinding

/**
 * Lightweight first-visit tips: a dim overlay, a ring around the target view
 * and a tip card ([R.layout.view_coach_tip]) whose pointer aims at the target's
 * centre. No third-party showcase library.
 *
 * A sequence of several tips counts them ("1 of 3") and offers a quiet Skip
 * beside Next; its last tip, and a lone tip, show only "Got it".
 */
class CoachMarkController(
    private val activity: Activity,
) {
    data class Step(
        val target: View,
        val message: String,
        val onEnter: (() -> Unit)? = null,
        val illustration: Int? = null,
    )

    private var overlay: CoachOverlay? = null
    private var steps: List<Step> = emptyList()
    private var index: Int = 0
    private var screen: CoachPrefs.Screen? = null

    /** Placement tries left for the current step while its target has no bounds yet. */
    private var placeTriesLeft = 0

    /**
     * Shows the tips if [screen] has not been seen. Marks it seen on Skip, and
     * when the sequence finishes through Next / Got it.
     */
    @Suppress("ReturnCount") // three independent "nothing to show" guards
    fun maybeShow(
        screen: CoachPrefs.Screen,
        steps: List<Step>,
        overlayParent: ViewGroup? = null,
    ) {
        if (steps.isEmpty()) return
        if (CoachPrefs.hasSeen(activity, screen)) return
        if (overlay != null) return
        this.screen = screen
        this.steps = steps
        this.index = 0
        val parent = overlayParent ?: activity.findViewById(android.R.id.content)
        overlay = CoachOverlay(activity, parent).also { built ->
            built.card.coachSkip.setOnClickListener { dismiss(markSeen = true) }
            built.card.coachNext.setOnClickListener { advance() }
        }
        showStep(0)
    }

    fun dismiss(markSeen: Boolean = true) {
        val shown = overlay ?: return
        shown.remove()
        overlay = null
        if (markSeen) {
            screen?.let { CoachPrefs.markSeen(activity, it) }
        }
        screen = null
        steps = emptyList()
    }

    private fun advance() {
        if (index >= steps.lastIndex) {
            dismiss(markSeen = true)
        } else {
            showStep(index + 1)
        }
    }

    private fun showStep(stepIndex: Int) {
        val card = overlay?.card ?: return
        index = stepIndex
        val step = steps[stepIndex]
        card.coachMessage.text = step.message
        bindIllustration(card.coachIllustration, step.illustration)

        val last = stepIndex == steps.lastIndex
        card.coachCount.isVisible = steps.size > 1
        card.coachCount.text = activity.getString(R.string.coach_step_count, stepIndex + 1, steps.size)
        card.coachSkip.isVisible = !last
        card.coachNext.setText(if (last) R.string.coach_done else R.string.coach_next)
        step.onEnter?.invoke()

        placeTriesLeft = MAX_PLACE_TRIES
        step.target.post { layoutAround(step.target) }
    }

    private fun bindIllustration(view: ImageView, res: Int?) {
        if (res != null) view.setImageResource(res) else view.setImageDrawable(null)
        view.isVisible = res != null
    }

    /**
     * Places the ring and the card around [target]. A target with no visible
     * bounds yet (not laid out, gone, or scrolled off) is retried once a frame
     * for about half a second, then the card shows unanchored. This used to
     * re-post itself with no limit, spinning the main thread for as long as the
     * target stayed hidden. A late post for a step already left does nothing.
     */
    private fun layoutAround(target: View) {
        val shown = overlay
        if (shown == null || steps.getOrNull(index)?.target !== target) return
        when {
            shown.placeAround(target) -> Unit
            placeTriesLeft-- > 0 -> target.postOnAnimation { layoutAround(target) }
            else -> shown.showUnanchored()
        }
    }

    private companion object {
        /** Frames to wait for a target's bounds: about half a second at 60 Hz. */
        const val MAX_PLACE_TRIES = 30
    }
}

/** The views of one shown sequence: scrim, target ring, tip card and its pointer. */
private class CoachOverlay(
    private val activity: Activity,
    parent: ViewGroup,
) {
    private val root = FrameLayout(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(ContextCompat.getColor(activity, R.color.coach_scrim))
        isClickable = true
        isFocusable = true
    }
    private val hole = buildHole()
    val card: ViewCoachTipBinding = ViewCoachTipBinding.inflate(LayoutInflater.from(activity), root, false)
    private val pointer = buildPointer()

    private val metrics = CoachTipGeometry.Metrics(
        margin = dp(16),
        maxWidth = dp(400),
        gap = dp(12),
        pointerWidth = dp(POINTER_WIDTH_DP),
        pointerHeight = dp(POINTER_HEIGHT_DP),
        pointerInset = dp(12),
    )

    init {
        // Hidden until placed, so the card does not flash at the top first.
        card.root.alpha = 0f
        root.addView(hole)
        root.addView(card.root)
        root.addView(pointer)
        parent.addView(root)
    }

    fun remove() {
        (root.parent as? ViewGroup)?.removeView(root)
    }

    /** Places everything around [target]; false while either view has no bounds yet. */
    fun placeAround(target: View): Boolean {
        val targetRect = Rect()
        val rootRect = Rect()
        val laidOut = target.getGlobalVisibleRect(targetRect) &&
            root.getGlobalVisibleRect(rootRect) &&
            !targetRect.isEmpty &&
            !rootRect.isEmpty
        if (laidOut) {
            val pad = dp(6)
            val ring = CoachTipGeometry.Box(
                left = (targetRect.left - rootRect.left - pad).coerceAtLeast(0),
                top = (targetRect.top - rootRect.top - pad).coerceAtLeast(0),
                right = targetRect.right - rootRect.left + pad,
                bottom = targetRect.bottom - rootRect.top + pad,
            )
            hole.isVisible = true
            position(hole, ring.left, ring.top, ring.width, ring.height)
            val safe = safeArea(rootRect)
            val width = CoachTipGeometry.cardWidth(safe, metrics)
            val placement = CoachTipGeometry.place(ring, safe, width, measureCard(width), metrics)
            applyPlacement(placement)
        }
        return laidOut
    }

    /** No target to point at: the card alone, centred in the safe area. */
    fun showUnanchored() {
        val safe = safeArea(Rect(0, 0, root.width, root.height))
        val width = CoachTipGeometry.cardWidth(safe, metrics)
        val height = measureCard(width)
        hole.isVisible = false
        pointer.isVisible = false
        position(card.root, safe.left + (safe.width - width) / 2, safe.top + (safe.height - height) / 2, width)
        card.root.alpha = 1f
    }

    private fun applyPlacement(placement: CoachTipGeometry.Placement) {
        val box = placement.card
        position(card.root, box.left, box.top, box.width)
        pointer.isVisible = placement.showPointer
        // Points up from a card below the target, down from one above it; the
        // 1 px tuck into the card hides the seam between the two.
        pointer.rotation = if (placement.pointsUp) 0f else 180f
        val tuck = if (placement.pointsUp) 1 else -1
        position(
            pointer,
            placement.pointerLeft,
            placement.pointerTop + tuck,
            metrics.pointerWidth,
            metrics.pointerHeight,
        )
        card.root.alpha = 1f
    }

    private fun measureCard(width: Int): Int {
        card.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return card.root.measuredHeight
    }

    /**
     * The part of the overlay clear of the system bars and display cutout, in the
     * overlay's own coordinates. Apps are edge-to-edge on targetSdk 35+, so the
     * content view the overlay usually sits in runs under both bars.
     */
    private fun safeArea(rootRect: Rect): CoachTipGeometry.Box {
        val bars = ViewCompat.getRootWindowInsets(root)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val window = root.rootView
        return CoachTipGeometry.Box(
            left = ((bars?.left ?: 0) - rootRect.left).coerceAtLeast(0),
            top = ((bars?.top ?: 0) - rootRect.top).coerceAtLeast(0),
            right = (window.width - (bars?.right ?: 0) - rootRect.left).coerceIn(0, root.width),
            bottom = (window.height - (bars?.bottom ?: 0) - rootRect.top).coerceIn(0, root.height),
        )
    }

    private fun position(
        view: View,
        left: Int,
        top: Int,
        width: Int,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    ) {
        view.layoutParams = FrameLayout.LayoutParams(width, height).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left
            topMargin = top
        }
    }

    private fun buildHole() = View(activity).apply {
        layoutParams = FrameLayout.LayoutParams(0, 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), ContextCompat.getColor(activity, R.color.sky_primary))
            cornerRadius = dp(8).toFloat()
        }
    }

    /**
     * An upward triangle in the card's colour. It is the view's background so
     * force dark inverts it exactly as it inverts the card's; it sits at the
     * card's elevation, drawn after it, with no shadow of its own.
     */
    private fun buildPointer() = View(activity).apply {
        layoutParams = FrameLayout.LayoutParams(0, 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isVisible = false
        val w = POINTER_WIDTH_DP.toFloat()
        val h = POINTER_HEIGHT_DP.toFloat()
        val path = Path().apply {
            moveTo(0f, h)
            lineTo(w / 2, 0f)
            lineTo(w, h)
            close()
        }
        background = ShapeDrawable(PathShape(path, w, h)).apply {
            paint.color = ContextCompat.getColor(activity, R.color.surface)
        }
        elevation = card.root.elevation
        outlineProvider = null
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val POINTER_WIDTH_DP = 16
        const val POINTER_HEIGHT_DP = 8
    }
}

/**
 * Where a tip card and its pointer go, in the overlay's pixels. Pure, so the
 * rules are tested without a screen: below the target when the card fits there
 * (or there is more room below than above), else above; always inside the safe
 * area; the pointer under the target's centre but clear of the card's corners.
 */
internal object CoachTipGeometry {

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerX: Int get() = (left + right) / 2
    }

    data class Metrics(
        /** Kept between the card and the safe area's edges. */
        val margin: Int,
        val maxWidth: Int,
        /** Between the target's ring and the card; the pointer sits in it. */
        val gap: Int,
        val pointerWidth: Int,
        val pointerHeight: Int,
        /** Kept between the pointer and the card's sides (its rounded corners). */
        val pointerInset: Int,
    )

    data class Placement(
        val card: Box,
        val pointerLeft: Int,
        val pointerTop: Int,
        /** True when the card is below the target, so the pointer points up. */
        val pointsUp: Boolean,
        /** False when the card had to overlap the target, leaving no room to point. */
        val showPointer: Boolean,
    )

    fun cardWidth(safe: Box, m: Metrics): Int =
        (safe.width - 2 * m.margin).coerceAtMost(m.maxWidth).coerceAtLeast(0)

    fun place(target: Box, safe: Box, width: Int, height: Int, m: Metrics): Placement {
        val roomBelow = safe.bottom - m.margin - (target.bottom + m.gap)
        val roomAbove = (target.top - m.gap) - (safe.top + m.margin)
        val below = roomBelow >= height || roomBelow >= roomAbove
        val wantTop = if (below) target.bottom + m.gap else target.top - m.gap - height
        val top = clamp(wantTop, safe.top + m.margin, safe.bottom - m.margin - height)
        val left = clamp(target.centerX - width / 2, safe.left + m.margin, safe.right - m.margin - width)
        val card = Box(left, top, left + width, top + height)
        val clear = if (below) {
            card.top - m.pointerHeight >= target.bottom
        } else {
            card.bottom + m.pointerHeight <= target.top
        }
        return Placement(
            card = card,
            pointerLeft = clamp(
                target.centerX - m.pointerWidth / 2,
                card.left + m.pointerInset,
                card.right - m.pointerInset - m.pointerWidth,
            ),
            pointerTop = if (below) card.top - m.pointerHeight else card.bottom,
            pointsUp = below,
            showPointer = clear,
        )
    }

    /** [value] within [lo]..[hi], or [lo] when the range is empty (a card too tall to fit starts at the top). */
    private fun clamp(value: Int, lo: Int, hi: Int): Int = if (hi < lo) lo else value.coerceIn(lo, hi)
}
