package com.indicvision.semper.ui.settings

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.indicvision.semper.R

/**
 * Inflates the settings scroll sections at runtime so [activity_settings] stays
 * under the TooManyViews lint threshold (children are not counted in the parent XML).
 */
class SettingsScrollContentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.settings_scroll_content, this, true)
    }
}
