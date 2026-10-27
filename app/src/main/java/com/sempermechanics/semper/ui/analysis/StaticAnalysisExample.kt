package com.sempermechanics.semper.ui.analysis

import androidx.core.net.toUri
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.navigation.IntentKeys

/*
 * A bundled example opened with Try it (ADR-031), on [StaticAnalysisActivity].
 * The photos come through the ordinary picker hand-off; this adds what a
 * picked photo cannot carry: the ROI drawn for the set and its load log.
 */

/**
 * Takes the example's ROI and load log off the Intent, once. The ROI goes in
 * with the reference's expected size before the reference is read:
 * `AnalysisViewModel.applyNewReference` keeps a drawn ROI when the photo
 * that arrives is that size, and drops it safely when it is not. The log is
 * read as if picked on the load card, which pairs it with the frames as they
 * arrive.
 */
internal fun StaticAnalysisActivity.consumeExampleExtras() {
    val roi = Roi.fromXywh(intent.getIntArrayExtra(IntentKeys.EXAMPLE_ROI))
    val size = intent.getIntArrayExtra(IntentKeys.EXAMPLE_IMAGE_SIZE)?.toImageSize()
    intent.removeExtra(IntentKeys.EXAMPLE_ROI)
    intent.removeExtra(IntentKeys.EXAMPLE_IMAGE_SIZE)
    if (roi != null && size != null) {
        viewModel.edit { it.copy(refSize = size, roi = roi, hasCustomRoi = true) }
    }
    intent.getStringExtra(IntentKeys.PICKED_LOAD_CSV_URI)?.let {
        intent.removeExtra(IntentKeys.PICKED_LOAD_CSV_URI)
        labHooks.loadCard?.onCsvPicked(it.toUri())
    }
}

/** Width and height; null when malformed. */
private fun IntArray.toImageSize(): ImageSize? = toList().takeIf { it.size == 2 }?.let { (w, h) -> ImageSize(w, h) }
