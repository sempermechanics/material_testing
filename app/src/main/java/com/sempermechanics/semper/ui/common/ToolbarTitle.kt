package com.sempermechanics.semper.ui.common

import android.text.TextUtils
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.children

/**
 * Lets this toolbar's title take [lines] lines before it ellipsizes. A Toolbar keeps
 * its title on one line, so at font scale 2.0 "New analysis" read "New analy…"
 * (readiness AE1V-001, AE1V-010). Call it after the title is first set: the title
 * view is created then, and later titles reuse it.
 */
fun Toolbar.letTitleWrap(lines: Int = 2) {
    children.filterIsInstance<TextView>().filter { it.text == title }.forEach {
        it.isSingleLine = false
        it.maxLines = lines
        it.ellipsize = TextUtils.TruncateAt.END
    }
}
