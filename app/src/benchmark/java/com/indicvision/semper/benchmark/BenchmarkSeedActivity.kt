@file:Suppress("MagicNumber")

package com.indicvision.semper.benchmark

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.indicvision.semper.DicKeys
import com.indicvision.semper.DicResult
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
 */
class BenchmarkSeedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frameCount = intent.getIntExtra(EXTRA_FRAME_COUNT, DEFAULT_FRAMES)
        val dir = seedSession(frameCount)

        val names = ArrayList<String>(frameCount)
        for (i in 0 until frameCount) names.add("Frame_${i + 1}")

        val viewer = Intent().apply {
            setClassName(this@BenchmarkSeedActivity, VIEWER)
            putExtra(DicKeys.BATCH_DIR_PATH, dir.absolutePath)
            putExtra(DicKeys.IMG_W, IMG_W)
            putExtra(DicKeys.IMG_H, IMG_H)
            putExtra(DicKeys.STEP, STEP)
            putExtra(DicKeys.ROI_X, 0)
            putExtra(DicKeys.ROI_Y, 0)
            putExtra(DicKeys.ROI_W, IMG_W)
            putExtra(DicKeys.ROI_H, IMG_H)
            putExtra(DicKeys.START_FRAME, 0)
            putStringArrayListExtra(DicKeys.DEF_FILE_NAMES, names)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(viewer)
        finish()
    }

    /** Writes `frame_%04d.dat` for [frameCount] frames into a cached session dir. */
    private fun seedSession(frameCount: Int): File {
        val dir = File(File(filesDir, "sessions"), "bench_${frameCount}_${COLS}x$ROWS")
        dir.mkdirs()
        val expected = File(dir, String.format("frame_%04d.dat", frameCount - 1))
        if (expected.exists()) return dir // already seeded
        for (i in 0 until frameCount) {
            File(dir, String.format("frame_%04d.dat", i)).writeBytes(frameBytes(i))
        }
        return dir
    }

    private fun frameBytes(seed: Int): ByteArray {
        val floats = FloatArray(COLS * ROWS * DicResult.STRIDE)
        var p = 0
        var k = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                floats[p + DicResult.IDX_X] = (c * STEP).toFloat()
                floats[p + DicResult.IDX_Y] = (r * STEP).toFloat()
                floats[p + DicResult.IDX_U] = (k - COLS * ROWS / 2) * 0.031f + seed
                floats[p + DicResult.IDX_V] = (COLS * ROWS / 2 - k) * 0.017f
                floats[p + DicResult.IDX_EXX] = (k % 9 - 4) * 0.00042f
                floats[p + DicResult.IDX_EYY] = (k % 6 - 3) * 0.00071f
                floats[p + DicResult.IDX_EXY] = (k % 4 - 2) * 0.00023f
                floats[p + DicResult.IDX_ZNSSD] = 0.01f
                p += DicResult.STRIDE
                k++
            }
        }
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(floats)
        return buf.array()
    }

    private companion object {
        const val EXTRA_FRAME_COUNT = "frameCount"
        const val DEFAULT_FRAMES = 150
        const val COLS = 160
        const val ROWS = 120
        const val STEP = 4
        const val IMG_W = COLS * STEP
        const val IMG_H = ROWS * STEP
        const val VIEWER = "com.indicvision.semper.ui.viewer.ResultViewerActivity"
    }
}
