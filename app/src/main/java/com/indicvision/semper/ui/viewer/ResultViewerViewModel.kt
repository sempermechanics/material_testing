package com.indicvision.semper.ui.viewer

import androidx.lifecycle.ViewModel
import com.indicvision.semper.report.StressStrain

/**
 * Survives configuration changes for the results browser: frame scrubber index
 * and active field type. Heavy bitmaps stay in the Activity (they are bound to
 * view lifetime).
 */
class ResultViewerViewModel : ViewModel() {
    var currentFrameIndex: Int = 0
    var currentDataIndex: Int = 2
    var currentTypeString: String = "U"

    /** Built on first Details open when the session has loads; a full batch decode, so kept. */
    var stressStrain: StressStrain.Curve? = null

    /** Results plot zoomed to the elastic region E is fitted to, on both surfaces; else the whole test. */
    var showElasticRegion: Boolean = false
}
