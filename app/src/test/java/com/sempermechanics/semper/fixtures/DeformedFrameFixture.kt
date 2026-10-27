package com.sempermechanics.semper.fixtures

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.ui.viewer.ViewerArgs
import com.sempermechanics.semper.ui.viewer.share.ViewerReportFactory
import org.junit.Assert.assertTrue
import java.io.File
import kotlin.math.abs

/**
 * A frame whose points all moved [U] px to the right, with a reference photo
 * and the frame's own photo in two flat colours, for the TD-141 export tests:
 * each export of a frame must draw what the viewer draws (ADR-011).
 *
 * The grid spans x [X0]..[X0] + 11·[STEP] at the reference and [U] px further
 * right in the frame, so [ONLY_DEFORMED] is covered by the displaced map alone
 * and [ONLY_REFERENCE] by the reference-position map alone. Both sit clear of
 * the info box, the colour bar and the MAX / MIN markers. Needs native graphics.
 */
object DeformedFrameFixture {
    const val W = 200
    const val H = 150
    const val STEP = 8
    private const val COLS = 12
    private const val ROWS = 9
    private const val X0 = 40
    private const val Y0 = 30
    const val U = 40f

    val REFERENCE_COLOR: Int = Color.rgb(90, 90, 90)
    val PHOTO_COLOR: Int = Color.rgb(20, 160, 60)

    /** Image pixel under the displaced map only. */
    val ONLY_DEFORMED = 150 to 60

    /** Image pixel under the reference-position map only. */
    val ONLY_REFERENCE = 55 to 60

    /** Exx rising left to right, every point moved by ([U], 0). */
    fun field(): FloatArray {
        val out = FloatArray(COLS * ROWS * DicResult.STRIDE)
        var p = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                out[p + DicResult.IDX_X] = (X0 + c * STEP).toFloat()
                out[p + DicResult.IDX_Y] = (Y0 + r * STEP).toFloat()
                out[p + DicResult.IDX_U] = U
                out[p + DicResult.IDX_EXX] = c * 0.001f
                out[p + DicResult.IDX_EYY] = r * 0.0005f
                out[p + DicResult.IDX_ZNSSD] = 0.01f
                p += DicResult.STRIDE
            }
        }
        return out
    }

    /** A flat [color] PNG of the image's size. */
    fun writePhoto(file: File, color: Int): File {
        val bmp = createBitmap(W, H)
        bmp.eraseColor(color)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    /**
     * The viewer's report source for one frame, its photo at [framePhoto]: a
     * path that is not on disk (or null) is a frame without its photo.
     */
    fun source(reference: File, framePhoto: File?): ViewerReportFactory.Source = ViewerReportFactory.Source(
        args = ViewerArgs(
            imgW = W,
            imgH = H,
            step = STEP,
            refName = "spec.png",
            refPath = reference.absolutePath,
            batchDirPath = null,
            frameNames = listOf("f0.png"),
            stopCode = 0,
            plannedFrames = 1,
            sessionId = null,
            sessionLocalId = "l1",
            subsetSize = 25,
            strainWindow = 9,
            engineStats = null,
            roiX = 0,
            roiY = 0,
            roiW = W,
            roiH = H,
            defFilePaths = listOfNotNull(framePhoto?.absolutePath),
            strainMethod = "VSG",
        ),
        imageSize = ImageSize(W, H),
        plannedFrames = listOf(0),
        defImagePaths = listOfNotNull(framePhoto?.absolutePath),
        displayBase = null,
    )

    /** [over] drawn at [alpha] (0..255) on opaque [under], per channel, as Canvas blends. */
    fun blend(over: Int, under: Int, alpha: Int): Int {
        fun ch(shift: Int): Int {
            val o = (over shr shift) and 0xFF
            val u = (under shr shift) and 0xFF
            return (o * alpha + u * (255 - alpha) + 127) / 255
        }
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    /** [actual] within [tolerance] of [expected] in every channel. */
    fun assertColor(message: String, expected: Int, actual: Int, tolerance: Int = 3) {
        val close = listOf(16, 8, 0).all { shift ->
            abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF)) <= tolerance
        }
        assertTrue(
            "$message: expected #%06X, got #%06X".format(expected and 0xFFFFFF, actual and 0xFFFFFF),
            close,
        )
    }
}
