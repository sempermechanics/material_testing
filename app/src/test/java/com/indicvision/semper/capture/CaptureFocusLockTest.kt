package com.indicvision.semper.capture

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.indicvision.semper.ui.analysis.SubsetRecommender
import com.indicvision.semper.ui.capture.CaptureFocusLock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class CaptureFocusLockTest {

    @Test
    fun `strong speckle is not low texture and yields a focus point`() {
        val (bytes, w, h) = syntheticJpeg(seed = 1, contrast = true)
        val rec = SubsetRecommender.recommend(bytes, w, h, Rect(0, 0, w, h))
        assertNotNull(rec)
        assertFalse(rec!!.lowTexture)
        assertTrue(rec.focusNormX in 0f..1f)
        assertTrue(rec.focusNormY in 0f..1f)
    }

    @Test
    fun `flat image is low texture`() {
        val (bytes, w, h) = syntheticJpeg(seed = 2, contrast = false)
        val rec = SubsetRecommender.recommend(bytes, w, h, Rect(0, 0, w, h))
        assertNotNull(rec)
        assertTrue(rec!!.lowTexture)
    }

    @Test
    fun `fromExif keeps focus point when distance is missing`() {
        val file = File.createTempFile("test", ".jpg")
        file.writeBytes(syntheticJpeg(seed = 3, contrast = true).first)
        val lock = CaptureFocusLock.fromExif(file, 0.42f, 0.58f, 640, 480)
        assertTrue(lock.normX in 0.41f..0.43f)
        assertTrue(lock.normY in 0.57f..0.59f)
        // Subject distance is commonly absent — null is allowed.
        assertTrue(lock.subjectDistanceM == null || (lock.subjectDistanceM ?: 0f) > 0f)
        file.delete()
    }

    private fun syntheticJpeg(seed: Int, contrast: Boolean): Triple<ByteArray, Int, Int> {
        val w = 256
        val h = 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rng = Random(seed)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = if (contrast) rng.nextInt(256) else 128
                bmp.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return Triple(out.toByteArray(), w, h)
    }
}
