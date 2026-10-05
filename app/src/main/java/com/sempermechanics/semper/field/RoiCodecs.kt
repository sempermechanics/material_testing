package com.sempermechanics.semper.field

import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import com.sempermechanics.semper.navigation.IntentKeys
import org.json.JSONObject

// Wire forms of a [Roi]. Each reads and writes exactly what the call sites do
// today, under the same key strings, so an Intent in a back stack, a saved
// Bundle or a cloud `metadata.json` written by an older build reads the same.

/**
 * Puts [roi] as the four `Int` extras [IntentKeys.ROI_X], [IntentKeys.ROI_Y],
 * [IntentKeys.ROI_W], [IntentKeys.ROI_H].
 */
fun Intent.putRoiExtras(roi: Roi): Intent = this
    .putExtra(IntentKeys.ROI_X, roi.x)
    .putExtra(IntentKeys.ROI_Y, roi.y)
    .putExtra(IntentKeys.ROI_W, roi.w)
    .putExtra(IntentKeys.ROI_H, roi.h)

/**
 * Reads the four `ROI_*` extras; each absent one reads [default]'s value. The
 * wizard reads the editor's result with `Roi.full(imageSize)` as the default,
 * which is what `StaticAnalysisActivity.applyRoiResult` does.
 */
fun Intent.getRoiExtras(default: Roi): Roi = Roi(
    getIntExtra(IntentKeys.ROI_X, default.x),
    getIntExtra(IntentKeys.ROI_Y, default.y),
    getIntExtra(IntentKeys.ROI_W, default.w),
    getIntExtra(IntentKeys.ROI_H, default.h),
)

/** Puts [roi] under [key] as an `[x, y, w, h]` int array (the wizard's saved state, key `"roi"`). */
fun Bundle.putRoi(key: String, roi: Roi) = putIntArray(key, roi.toXywh())

/** The ROI [putRoi] stored under [key], or null when it is absent or not four ints. */
fun Bundle.getRoi(key: String): Roi? = Roi.fromXywh(getIntArray(key))

/**
 * Saves the ROI editor's float selection (image pixels) under
 * [IntentKeys.ROI_L], [IntentKeys.ROI_T], [IntentKeys.ROI_R], [IntentKeys.ROI_B], as
 * `RoiDrawActivity.onSaveInstanceState` does. It stays a [RectF]: the editor
 * restores it unrounded.
 */
fun Bundle.putRoiEdges(edges: RectF) {
    putFloat(IntentKeys.ROI_L, edges.left)
    putFloat(IntentKeys.ROI_T, edges.top)
    putFloat(IntentKeys.ROI_R, edges.right)
    putFloat(IntentKeys.ROI_B, edges.bottom)
}

/**
 * The selection [putRoiEdges] saved, or null when there is none. Absence is a
 * left edge of `-1f`, the sentinel `RoiDrawActivity.onCreate` reads with.
 */
fun Bundle.getRoiEdges(): RectF? {
    val left = getFloat(IntentKeys.ROI_L, NO_EDGE)
    if (left == NO_EDGE) return null
    return RectF(left, getFloat(IntentKeys.ROI_T), getFloat(IntentKeys.ROI_R), getFloat(IntentKeys.ROI_B))
}

private const val NO_EDGE = -1f

/** Key of the ROI object inside `metadata.json`'s `engine` object. */
const val ROI_JSON_KEY = "roi"

/** `{"x":…, "y":…, "w":…, "h":…}`, in that key order, as `SessionUploadMetadata.engineJson` writes it. */
fun Roi.toJson(): JSONObject = JSONObject().put("x", x).put("y", y).put("w", w).put("h", h)

/** Reads [Roi.toJson]'s form; a missing object or key reads 0, as `SessionMetadataDoc.toRecord` does. */
fun roiFromJson(json: JSONObject?): Roi {
    val o = json ?: JSONObject()
    return Roi(o.optInt("x", 0), o.optInt("y", 0), o.optInt("w", 0), o.optInt("h", 0))
}
