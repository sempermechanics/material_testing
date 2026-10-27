package com.sempermechanics.semper.ui.analysis

import android.content.Context
import androidx.annotation.StringRes
import com.sempermechanics.semper.R

/** Every combination of a sweep solves the same one deformed image. */
private const val SWEEP_DEFORMED_IMAGES = 1

/**
 * Study type, image names, and settings for the header of [VsgLatticeActivity]'s
 * shared graph: the plot's [strainLabel] along the line cut ([horizontal] or
 * not), the reference [refName] when known, the selected combination's
 * [nodeLabel], and [comboCount] curves (null in Isolate mode, which draws one).
 */
internal fun Context.vsgExportHeaderLines(
    horizontal: Boolean,
    @StringRes strainLabel: Int,
    refName: String,
    nodeLabel: String?,
    comboCount: Int?,
): List<String> {
    val lines = mutableListOf<String>()
    val axis = getString(if (horizontal) R.string.axis_x else R.string.axis_y)
    lines += getString(
        R.string.vsg_export_title_fmt,
        getString(R.string.settings_used_vsg),
        getString(strainLabel),
        axis,
    )
    if (refName.isNotBlank()) {
        // A sweep solves one deformed image (`RunSpec.Sweep.frameIndex`) with
        // every combination. `frameNames` holds the combination labels, and
        // counting those made a 12-node sweep "12 deformed images".
        lines += resources.getQuantityString(
            R.plurals.vsg_export_images_fmt,
            SWEEP_DEFORMED_IMAGES,
            refName,
            SWEEP_DEFORMED_IMAGES,
        )
    }
    if (nodeLabel != null) lines += nodeLabel
    if (comboCount != null) {
        lines += resources.getQuantityString(R.plurals.vsg_export_combos_fmt, comboCount, comboCount)
    }
    return lines
}
