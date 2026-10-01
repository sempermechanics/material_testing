package com.indicvision.semper.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import com.indicvision.semper.R

/**
 * The collapsible Settings section header (`settings_section_header.xml`).
 *
 * Settings repeats the header block seven times in `settings_scroll_content.xml`,
 * each with its own title, and its chevron described by that same title. An
 * `<include>` cannot set a child's text, so [bind] does: give the include the
 * header's id (`headerAccount`, …) and bind it once. The chevron is then
 * `header.findViewById(R.id.ivSectionChevron)` — one id in every include, so
 * look it up under its header, never from the Activity.
 */
object SettingsSectionHeader {

    /** The header's layout, for a section built in code. */
    val layout: Int = R.layout.settings_section_header

    /** Titles [header] [title] and describes its chevron with the same text. */
    fun bind(header: View, @StringRes title: Int) {
        val text = header.context.getText(title)
        header.findViewById<TextView>(R.id.tvSectionTitle).text = text
        chevron(header).contentDescription = text
    }

    /** A header inflated into [parent] (not attached) and bound to [title]. */
    fun inflate(inflater: LayoutInflater, parent: ViewGroup, @StringRes title: Int): View =
        inflater.inflate(layout, parent, false).also { bind(it, title) }

    /** The chevron under [header], which the section's expand / collapse rotates. */
    fun chevron(header: View): ImageView = header.findViewById(R.id.ivSectionChevron)
}
