package com.sempermechanics.semper.results

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.ValueRange
import com.sempermechanics.semper.report.HeatmapRequest
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.viewer.HeatmapFit
import com.sempermechanics.semper.ui.viewer.summary.SummaryAnimation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * The summary GIF draws every frame in the deformed configuration, as the
 * viewer draws a frame on its own photo (ADR-011, TD-141): each frame's palette
 * indices are [VisualizationEngine.generateDeformedHeatmapIndices], cropped to
 * one box that also takes in where the points moved to. Read back with the
 * JDK's GIF reader. Robolectric for the jet palette.
 */
@RunWith(RobolectricTestRunner::class)
class SummaryGifDeformedTest {

    @get:Rule
    val temp = TemporaryFolder()

    private companion object {
        const val W = 80
        const val H = 60
        const val STEP = 4
        const val COLS = 12
        const val ROWS = 9
        const val X0 = 16
        const val Y0 = 12
        const val FRAMES = 3
        val BOUNDS = ValueRange(0f, 0.012f)
    }

    /** Frame [f]: Exx rising left to right, every point moved (4 + 4·f, 0) px. */
    private fun frame(f: Int): FloatArray {
        val out = FloatArray(COLS * ROWS * DicResult.STRIDE)
        var p = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                out[p + DicResult.IDX_X] = (X0 + c * STEP).toFloat()
                out[p + DicResult.IDX_Y] = (Y0 + r * STEP).toFloat()
                out[p + DicResult.IDX_U] = 4f + 4f * f
                out[p + DicResult.IDX_EXX] = c * 0.001f
                out[p + DicResult.IDX_ZNSSD] = 0.01f
                p += DicResult.STRIDE
            }
        }
        return out
    }

    private fun write(dir: File, f: Int, data: FloatArray): File {
        val buffer = ByteBuffer.allocate(data.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        data.forEach { buffer.putFloat(it) }
        return File(dir, "frame_%04d.dat".format(f)).apply { writeBytes(buffer.array()) }
    }

    /** Each GIF frame as palette indices, row-major. */
    private fun readIndices(bytes: ByteArray): List<Pair<Pair<Int, Int>, ByteArray>> {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        return (0 until reader.getNumImages(true)).map { i ->
            val image = reader.read(i)
            val raster = image.raster
            val out = ByteArray(image.width * image.height) { k ->
                raster.getSample(k % image.width, k / image.width, 0).toByte()
            }
            (image.width to image.height) to out
        }
    }

    @Test
    fun `every frame is the deformed render, in a box that takes in the movement`() {
        val frames = (0 until FRAMES).map(::frame)
        val dir = temp.newFolder()
        val anim = SummaryAnimation(
            SummaryAnimation.Spec(
                batchFiles = frames.mapIndexed { f, data -> write(dir, f, data) },
                imgW = W,
                imgH = H,
                stepAt = { STEP },
                outputDir = temp.newFolder(),
                backgroundColor = 0xFF101518.toInt(),
            ),
        )
        val gif = checkNotNull(runBlocking { anim.build(DicResult.IDX_EXX, "Exx", BOUNDS) })
        val read = readIndices(gif.readBytes())
        assertEquals(FRAMES, read.size)

        // The first frame's points at the reference, widened to where the last
        // frame's moved: x 16..(60 + 12), y 12..44.
        val fit = floatArrayOf(16f, 12f, 72f, 44f)
        val cap = HeatmapFit.renderLongEdgeCap(W, H, fit, SummaryAnimation.MAX_EDGE)
        frames.forEachIndexed { f, data ->
            fun expected(plane: VisualizationEngine.IndexPlane) =
                HeatmapFit.cropAndScale(plane, W, H, fit, SummaryAnimation.MAX_EDGE)
            val deformed = expected(
                VisualizationEngine.indices(
                    HeatmapRequest(
                        data = data,
                        size = ImageSize(W, H),
                        valIndex = DicResult.IDX_EXX,
                        step = STEP,
                        bounds = BOUNDS,
                        maxLongEdge = cap,
                        displaced = true,
                    ),
                ),
            )
            val reference = expected(
                VisualizationEngine.indices(
                    HeatmapRequest(
                        data = data,
                        size = ImageSize(W, H),
                        valIndex = DicResult.IDX_EXX,
                        step = STEP,
                        bounds = BOUNDS,
                        maxLongEdge = cap,
                    ),
                ),
            )
            val (size, indices) = read[f]
            assertEquals("frame $f size", deformed.width to deformed.height, size)
            assertArrayEquals("frame $f is the deformed render", deformed.indices, indices)
            assertFalse("frame $f differs from the reference render", reference.indices.contentEquals(indices))
        }
    }
}
