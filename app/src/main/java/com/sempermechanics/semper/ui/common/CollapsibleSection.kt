package com.sempermechanics.semper.ui.common

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import com.sempermechanics.semper.R

/**
 * A section a tap on its [header] opens and closes, starting closed: the
 * wizard pages' "Advanced" (`view_advanced_header.xml`). Open, the [chevron]
 * points up; the change animates over [container]
 * ([Motion.animateExpandCollapse]), as Settings' sections do. Whether it is
 * open is not kept: the page shows it closed again after a recreate.
 */
class CollapsibleSection(
    private val header: View,
    private val chevron: View,
    private val body: View,
    private val container: ViewGroup,
) {
    /** True while [body] shows. */
    val isExpanded: Boolean get() = body.isVisible

    init {
        show(expanded = false)
        header.setOnClickListener { set(expanded = !isExpanded) }
    }

    /** Opens the section, unless it is open already. */
    fun expand() {
        if (!isExpanded) set(expanded = true)
    }

    private fun set(expanded: Boolean) {
        Motion.animateExpandCollapse(container)
        show(expanded)
    }

    private fun show(expanded: Boolean) {
        body.isVisible = expanded
        chevron.rotation = if (expanded) EXPANDED_DEG else 0f
        ExpandState.mark(header, expanded) { set(expanded = !isExpanded) }
    }

    private companion object {
        const val EXPANDED_DEG = 180f
    }
}

/**
 * Tells TalkBack whether a collapsible section's header row is open: it reads
 * "Expanded" or "Collapsed" after the title and offers the matching expand or
 * collapse action, which a tap on the header performs. Before, only the
 * chevron turned (readiness AE1-107).
 */
object ExpandState {

    /** Marks [header] [expanded]; [toggle] is what a tap on it does. */
    fun mark(header: View, expanded: Boolean, toggle: () -> Unit) {
        ViewCompat.setStateDescription(
            header,
            header.context.getString(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed),
        )
        ViewCompat.removeAccessibilityAction(header, AccessibilityActionCompat.ACTION_EXPAND.id)
        ViewCompat.removeAccessibilityAction(header, AccessibilityActionCompat.ACTION_COLLAPSE.id)
        val action = if (expanded) AccessibilityActionCompat.ACTION_COLLAPSE else AccessibilityActionCompat.ACTION_EXPAND
        ViewCompat.replaceAccessibilityAction(header, action, null) { _, _ ->
            toggle()
            true
        }
    }
}
