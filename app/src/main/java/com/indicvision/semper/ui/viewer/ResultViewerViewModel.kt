package com.indicvision.semper.ui.viewer

import androidx.lifecycle.ViewModel
import java.util.concurrent.ConcurrentHashMap

/**
 * Survives configuration changes for the results browser: frame scrubber index,
 * active field type and the user's fixed colour scales. Heavy bitmaps stay in
 * the Activity (they are bound to view lifetime).
 */
class ResultViewerViewModel : ViewModel() {
    var currentFrameIndex: Int = 0
    var currentDataIndex: Int = 2
    var currentTypeString: String = "U"

    /**
     * Fixed colour-scale bounds per field, set in the custom-scale dialog.
     * Concurrent: an export reads them off the main thread (the GIF bounds)
     * while the dialog may change them.
     */
    val customBounds: MutableMap<Int, Pair<Float, Float>> = ConcurrentHashMap()
}
