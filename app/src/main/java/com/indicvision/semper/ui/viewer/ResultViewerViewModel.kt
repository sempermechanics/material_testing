package com.indicvision.semper.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Survives configuration changes for the results browser: frame scrubber index,
 * active field type, the user's fixed colour scales and running exports. Heavy
 * bitmaps stay in the Activity (they are bound to view lifetime).
 */
class ResultViewerViewModel(app: Application) : AndroidViewModel(app) {
    var currentFrameIndex: Int = 0
    var currentDataIndex: Int = 2
    var currentTypeString: String = "U"

    /**
     * Fixed colour-scale bounds per field, set in the custom-scale dialog.
     * Concurrent: an export reads them off the main thread (the GIF bounds)
     * while the dialog may change them.
     */
    val customBounds: MutableMap<Int, Pair<Float, Float>> = ConcurrentHashMap()

    /** Share/export jobs; in [viewModelScope], so a rotation does not cancel them. */
    internal val exports = ShareExportJobs(viewModelScope, app.contentResolver)
}
