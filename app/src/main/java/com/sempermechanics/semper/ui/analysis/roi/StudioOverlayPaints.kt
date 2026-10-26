package com.sempermechanics.semper.ui.analysis.roi

import android.content.Context
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.content.ContextCompat
import com.sempermechanics.semper.R

/**
 * The paints [StudioOverlayView] draws with, one set per view: the dim and the
 * clear that cuts the crop out of it, the crop outline and handles, the holes.
 * Their colours are the roi_studio_* tokens.
 */
internal class StudioOverlayPaints(context: Context) {
    val holeFillPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.roi_studio_hole_fill)
        style = Paint.Style.FILL
    }
    val holeBorderPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.roi_studio_hole_stroke)
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
    }
    val borderPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.roi_studio_crop)
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
    }
    val handlePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.roi_studio_handle)
        style = Paint.Style.FILL
    }
    val dimPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.roi_studio_dim)
        style = Paint.Style.FILL
    }
    val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL
    }

    private companion object {
        /** Crop and hole outlines, view px. */
        const val STROKE_WIDTH = 5f
    }
}
