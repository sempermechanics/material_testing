package com.sempermechanics.semper.ui.analysis.roi

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.graphics.toColorInt

/**
 * The paints [StudioOverlayView] draws with, one set per view: the dim and the
 * clear that cuts the crop out of it, the crop outline and handles, the holes.
 */
internal class StudioOverlayPaints {
    val holeFillPaint = Paint().apply {
        color = "#88FF0000".toColorInt()
        style = Paint.Style.FILL
    }
    val holeBorderPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
    }
    val borderPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
    }
    val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val dimPaint = Paint().apply {
        color = "#99000000".toColorInt()
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
