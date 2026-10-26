package com.sempermechanics.semper.ui.analysis.sweep

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View.MeasureSpec
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.sempermechanics.semper.R

/**
 * Renders the current plot at [widthPx]×[heightPx] (full resolution, not a
 * screenshot of the on-screen size). Restores the view's prior layout after.
 */
fun SweepPlotView.renderToBitmap(widthPx: Int, heightPx: Int): Bitmap {
    val prevW = width
    val prevH = height
    val wSpec = MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY)
    val hSpec = MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.EXACTLY)
    measure(wSpec, hSpec)
    layout(0, 0, widthPx, heightPx)
    val bitmap = createBitmap(widthPx, heightPx)
    val canvas = Canvas(bitmap)
    canvas.drawColor(ContextCompat.getColor(context, R.color.sweep_export_page))
    draw(canvas)
    if (prevW > 0 && prevH > 0) {
        measure(
            MeasureSpec.makeMeasureSpec(prevW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(prevH, MeasureSpec.EXACTLY),
        )
        layout(left, top, left + prevW, top + prevH)
    } else {
        requestLayout()
    }
    return bitmap
}
