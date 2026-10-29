package com.sempermechanics.semper.data.session

import android.content.Context
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.cloud.SessionMetadataSync
import com.sempermechanics.semper.data.mechanical.CurveCorrection
import com.sempermechanics.semper.report.BeamDeflection

// The lab tests' Results corrections on a saved row: semper Classroom only. They
// sat in SessionStore until upstream's growth took that file over the ADR-015
// 500-line budget; moved here as they were, on SessionStore's public update.

/**
 * Saves the tensile curve's scale and bias from Results. Like [SessionStore.rename] it
 * leaves the sync state alone. A session with a cloud copy, or one on its
 * way, is marked [SessionRecord.metadataStale] when the correction
 * changes, so [SessionMetadataSync] re-sends its metadata.json (TD-151).
 */
@WorkerThread
fun SessionStore.setCurveCorrection(context: Context, id: String, correction: CurveCorrection) = update(context, id) {
    val changed = it.curveCorrection != correction
    it.copy(
        curveCorrection = correction,
        metadataStale = it.metadataStale || (changed && it.hasCloudCopy),
        updatedAt = System.currentTimeMillis(),
    )
}

/**
 * Bending's deflection scale and bias, set in the viewer's Results. Like
 * [SessionStore.rename] it leaves the sync state alone. A session with a cloud copy, or
 * one on its way, is marked [SessionRecord.metadataStale] when the
 * correction changes, so [SessionMetadataSync] re-sends its metadata.json.
 */
@WorkerThread
fun SessionStore.setDeflectionCorrection(context: Context, id: String, correction: BeamDeflection.Correction) =
    update(context, id) {
        val changed = it.geometry.deflectionCorrection != correction
        it.copy(
            geometry = it.geometry.withCorrection(correction),
            metadataStale = it.metadataStale || (changed && it.hasCloudCopy),
            updatedAt = System.currentTimeMillis(),
        )
    }
