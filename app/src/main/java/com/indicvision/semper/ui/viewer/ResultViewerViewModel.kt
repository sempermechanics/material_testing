package com.indicvision.semper.ui.viewer

import androidx.lifecycle.ViewModel

/**
 * Survives configuration changes for the results browser: frame scrubber index
 * and active field type. Heavy bitmaps stay in the Activity (they are bound to
 * view lifetime).
 */
class ResultViewerViewModel : ViewModel() {
    var currentFrameIndex: Int = 0
    var currentDataIndex: Int = 2
    var currentTypeString: String = "U"
}
