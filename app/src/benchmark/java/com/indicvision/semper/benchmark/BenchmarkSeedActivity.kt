@file:Suppress("MagicNumber")

package com.indicvision.semper.benchmark

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.indicvision.semper.DicResult
import com.indicvision.semper.data.SessionPaths
import com.indicvision.semper.ui.viewer.ViewerArgs
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug-free, benchmark-variant-only launcher that fabricates a synthetic N-frame DIC
 * session and opens it in [com.indicvision.semper.ui.viewer.ResultViewerActivity], so
 * Macrobenchmark can profile the viewer without real credentials, images, or a native
 * engine run. Lives in `src/benchmark` ⇒ compiled **only** into the release-like
 * `benchmark` variant, never into `debug` or the shipped `release`.
 *
 * Extras: `frameCount` (default 150) and `pointsPerFrame` is fixed by the COLS×ROWS grid
 * below so the payload matches [HotPathMicroBenchmark]. Frames are cached on disk keyed
 * by frame count, so repeated benchmark iterations pay the fabrication cost once.
 *
 * `results` (boolean) seeds a tensile test instead: a load per frame and a mean Exx
 * that rises with it, straight for the first two thirds (200 GPa at 10 mm²), then
 * flattening. The viewer then opens on its Results page, which is what
 * `LabResultsBenchmark` measures.
 */
class BenchmarkSeedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frameCount = intent.getIntExtra(EXTRA_FRAME_COUNT, DEFAULT_FRAMES)
        val results = intent.getBooleanExtra(EXTRA_RESULTS, false)
        val dir = seedSession(frameCount, results)

        val names = ArrayList<String>(frameCount)
        for (i in 0 until frameCount) names.add("Frame_${i + 1}")

        val frames = ViewerArgs.ofFrames(
            batchDir = dir.absolutePath,
            imgW = IMG_W,
            imgH = IMG_H,
            step = STEP,
            frameNames = names,
            startFrame = 0,
        )
        val args = if (results) {
            frames.copy(
                startFrame = null,
                testType = TENSILE,
                crossSectionMm2 = CROSS_SECTION_MM2,
                loadsN = List(frameCount) { stressMPa(it, frameCount) * CROSS_SECTION_MM2 },
            )
        } else {
            frames
        }
        val viewer = args.toIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(viewer)
        finish()
    }

    /**
     * Writes `frame_%04d.dat` for [frameCount] frames into a cached session dir.
     *
     * The scratch `FloatArray`/`ByteBuffer` are allocated **once** and refilled per
     * frame. Allocating them per frame (~1.2 MB each) grew the Java heap by well over
     * 100 MB while seeding 150 frames, and since that happens inside the app process it
     * landed in `MemoryUsageMetric` — i.e. the harness would have been measuring its own
     * fabrication cost instead of the viewer's.
     */
    private fun seedSession(frameCount: Int, results: Boolean): File {
        val kind = if (results) "results" else "bench"
        val dir = File(File(filesDir, "sessions"), "${kind}_${frameCount}_${COLS}x$ROWS")
        dir.mkdirs()
        val expected = SessionPaths.frameDat(dir, frameCount - 1)
        if (expected.exists()) return dir // already seeded
        val floats = FloatArray(COLS * ROWS * DicResult.STRIDE)
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frameCount) {
            fillFrame(floats, seed = i, exxOffset = if (results) strainMilli(i, frameCount) / 1000f else 0f)
            buf.clear()
            buf.asFloatBuffer().put(floats)
            SessionPaths.frameDat(dir, i).writeBytes(buf.array())
        }
        return dir
    }

    private fun fillFrame(floats: FloatArray, seed: Int, exxOffset: Float) {
        var p = 0
        var k = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                floats[p + DicResult.IDX_X] = (c * STEP).toFloat()
                floats[p + DicResult.IDX_Y] = (r * STEP).toFloat()
                floats[p + DicResult.IDX_U] = (k - COLS * ROWS / 2) * 0.031f + seed
                floats[p + DicResult.IDX_V] = (COLS * ROWS / 2 - k) * 0.017f
                floats[p + DicResult.IDX_EXX] = (k % 9 - 4) * 0.00042f + exxOffset
                floats[p + DicResult.IDX_EYY] = (k % 6 - 3) * 0.00071f
                floats[p + DicResult.IDX_EXY] = (k % 4 - 2) * 0.00023f
                floats[p + DicResult.IDX_ZNSSD] = 0.01f
                p += DicResult.STRIDE
                k++
            }
        }
    }

    /** Mean strain of [frame] in mε: 0.1 mε a frame over the elastic two thirds, then 0.3 mε. */
    private fun strainMilli(frame: Int, frameCount: Int): Float {
        val elastic = frameCount * 2 / 3
        return if (frame < elastic) 0.1f * (frame + 1) else 0.1f * elastic + 0.3f * (frame + 1 - elastic)
    }

    /** σ = 200·ε over the elastic frames, then 10 MPa a frame, so the last frame is the peak. */
    private fun stressMPa(frame: Int, frameCount: Int): Float {
        val elastic = frameCount * 2 / 3
        return if (frame < elastic) {
            MODULUS_MPA_PER_MILLI * strainMilli(frame, frameCount)
        } else {
            MODULUS_MPA_PER_MILLI * strainMilli(elastic - 1, frameCount) + 10f * (frame + 1 - elastic)
        }
    }

    private companion object {
        const val EXTRA_FRAME_COUNT = "frameCount"
        const val EXTRA_RESULTS = "results"
        const val TENSILE = "tensile"
        const val CROSS_SECTION_MM2 = 10f
        const val MODULUS_MPA_PER_MILLI = 200f
        const val DEFAULT_FRAMES = 150
        const val COLS = 160
        const val ROWS = 120
        const val STEP = 4
        const val IMG_W = COLS * STEP
        const val IMG_H = ROWS * STEP
    }
}
