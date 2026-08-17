package com.indicvision.semper.util

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.widget.ImageView
import com.indicvision.semper.R

/**
 * The wordmark PNG is an opaque black plate with dark “semper” and cyan “DIC”.
 * Punch the plate so callers can fill the background from context (sky / black
 * in-app, white on PDF pages).
 */
object BrandAssets {

    fun punchBlackPlate(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val w = out.width
        val h = out.height
        if (w <= 0 || h <= 0) return out
        val row = IntArray(w)
        for (y in 0 until h) {
            out.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                // Plate is #000000. Keep ~#171719 glyphs and cyan DIC.
                if (r <= PLATE_MAX && g <= PLATE_MAX && b <= PLATE_MAX) {
                    row[x] = Color.TRANSPARENT
                }
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    fun wordmarkForPdf(resources: Resources): Bitmap? {
        val raw = BitmapFactory.decodeResource(resources, R.drawable.semper_wordmark) ?: return null
        val punched = punchBlackPlate(raw)
        if (punched !== raw && !raw.isRecycled) raw.recycle()
        return punched
    }

    fun bindLoginWordmark(imageView: ImageView) {
        val punched = wordmarkForPdf(imageView.resources) ?: return
        imageView.setImageBitmap(punched)
        imageView.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    }

    private const val PLATE_MAX = 8
}
