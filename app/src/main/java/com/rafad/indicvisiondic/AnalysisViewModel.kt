package com.rafad.indicvisiondic

import androidx.lifecycle.ViewModel

class AnalysisViewModel : ViewModel() {

    // Store the heavy image data
    var refBytes: ByteArray? = null
    var defBytes: ByteArray? = null
    var roiMaskBytes: ByteArray? = null

    // Store Image Info
    var realRefWidth: Int = 0
    var realRefHeight: Int = 0
    var refName: String = "No image selected"
    var defName: String = "No image selected"

    // Store ROI Info
    var hasCustomRoi: Boolean = false
    var roiX: Int = 0
    var roiY: Int = 0
    var roiW: Int = 0
    var roiH: Int = 0

    // Store Last Results State
    var lastStep: Int = 5
    var lastDataPath: String? = null
    var lastDefPath: String? = null
    var hasCompletedAnalysis: Boolean = false

    // UI Helpers
    fun isReadyToCompute(): Boolean {
        return refBytes != null && defBytes != null
    }
}