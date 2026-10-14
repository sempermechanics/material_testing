@file:Suppress("MagicNumber")

package com.sempermechanics.semper.benchmark

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.core.graphics.createBitmap
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.FieldRangesStore
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.viewer.ViewerArgs
import com.sempermechanics.semper.ui.viewer.summary.SummaryAnimation
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug-free, benchmark-variant-only launcher that fabricates a synthetic N-frame DIC
 * session and opens it in [com.sempermechanics.semper.ui.viewer.ResultViewerActivity], so
 * Macrobenchmark can profile the viewer without real credentials, images, or a native
 * engine run. Lives in `src/benchmark` ⇒ compiled **only** into the release-like
 * `benchmark` variant, never into `debug` or the shipped `release`.
 *
 * Extras: `frameCount` (default 150) and `pointsPerFrame` is fixed by the COLS×ROWS grid
 * below so the payload matches [HotPathMicroBenchmark]. Frames are cached on disk keyed
 * by frame count, so repeated benchmark iterations pay the fabrication cost once.
 *
 * `framePhotos` (default false) seeds a separate session whose every frame has its own
 * photo in `raw_deformed/`, so the viewer draws each frame over it with
 * `VisualizationEngine.generateDeformedHeatmap` (ADR-011, TD-175). Its frames move by a
 * moderate u = 0.02·x, v = 0.01·y px rather than the default frames' ~300 px drift, which
 * would warp most of the map off the canvas. The photos are one flat grey PNG, so their
 * decode is cheaper than a camera photo's. The default session, which the gated
 * `scrub10Frames` / `scrub150Frames` runs open, is not touched.
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
        val framePhotos = intent.getBooleanExtra(EXTRA_FRAME_PHOTOS, false)
        val results = intent.getBooleanExtra(EXTRA_RESULTS, false)
        val names = ArrayList<String>(frameCount)
        for (i in 0 until frameCount) names.add("Frame_${i + 1}")
        val dir = seedSession(frameCount, if (framePhotos) names else null, results)

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
     *
     * It also writes the [FieldRangesStore] sidecar that `BatchAnalysis` writes for a
     * real batch, so the viewer's colour-scale pass reads it instead of decoding every
     * frame. Without it the benchmark measured only the no-sidecar fallback (older or
     * restored sessions), whose ~1 MB per frame of garbage set the 150-frame heap
     * (TD-87). The per-frame `valueRanges` columns are allocated here, in setup and on
     * the first iteration only.
     *
     * With [photoNames] (the `framePhotos` extra) the session is its own dir, its frames
     * move moderately, and each frame gets a photo under its name in `raw_deformed/`.
     * With [results] it is a tensile test's session of its own (see the class doc).
     */
    private fun seedSession(frameCount: Int, photoNames: List<String>?, results: Boolean): File {
        val suffix = if (photoNames != null) "_photos" else ""
        val kind = if (results) "results" else "bench"
        val dir = File(File(filesDir, "sessions"), "${kind}_${frameCount}_${COLS}x$ROWS$suffix")
        dir.mkdirs()
        val expected = SessionPaths.frameDat(dir, frameCount - 1)
        val rangesFile = File(dir, FieldRangesStore.FILE_NAME)
        val photoDir = File(dir, SessionPaths.RAW_DEFORMED_SUBDIR)
        val photosSeeded = photoNames == null || File(photoDir, photoNames.last()).isFile
        if (expected.exists() && rangesFile.exists() && photosSeeded) return dir // already seeded
        val fieldIndices = SummaryAnimation.FIELDS.map { it.second }.toIntArray()
        val perFrameRanges = ArrayList<Map<Int, Pair<Float, Float>?>>(frameCount)
        val floats = FloatArray(COLS * ROWS * DicResult.STRIDE)
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frameCount) {
            fillFrame(
                floats,
                seed = i,
                moderate = photoNames != null,
                exxOffset = if (results) strainMilli(i, frameCount) / 1000f else 0f,
            )
            buf.clear()
            buf.asFloatBuffer().put(floats)
            SessionPaths.frameDat(dir, i).writeBytes(buf.array())
            perFrameRanges.add(VisualizationEngine.valueRanges(floats, fieldIndices))
        }
        FieldRangesStore.write(rangesFile, fieldIndices, perFrameRanges)
        if (photoNames != null) writeFramePhotos(photoDir, photoNames)
        return dir
    }

    /** One flat grey [IMG_W]×[IMG_H] PNG, encoded once and written under each frame's name. */
    private fun writeFramePhotos(photoDir: File, names: List<String>) {
        photoDir.mkdirs()
        val bitmap = createBitmap(IMG_W, IMG_H)
        bitmap.eraseColor(PHOTO_GREY)
        val png = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, png)
        bitmap.recycle()
        val bytes = png.toByteArray()
        for (name in names) File(photoDir, name).writeBytes(bytes)
    }

    /**
     * [moderate]: u = 0.02·x, v = 0.01·y px plus a small per-frame shift, for the photo
     * session. [exxOffset] stretches x by that much, which the tensile curve's ΔL / L₀ reads.
     */
    private fun fillFrame(floats: FloatArray, seed: Int, moderate: Boolean, exxOffset: Float) {
        var p = 0
        var k = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                floats[p + DicResult.IDX_X] = (c * STEP).toFloat()
                floats[p + DicResult.IDX_Y] = (r * STEP).toFloat()
                if (moderate) {
                    floats[p + DicResult.IDX_U] = c * STEP * 0.02f + seed * 0.01f
                    floats[p + DicResult.IDX_V] = r * STEP * 0.01f
                } else {
                    floats[p + DicResult.IDX_U] = (k - COLS * ROWS / 2) * 0.031f + seed + exxOffset * c * STEP
                    floats[p + DicResult.IDX_V] = (COLS * ROWS / 2 - k) * 0.017f
                }
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
        const val EXTRA_FRAME_PHOTOS = "framePhotos"
        const val EXTRA_RESULTS = "results"
        const val TENSILE = "tensile"
        const val CROSS_SECTION_MM2 = 10f
        const val MODULUS_MPA_PER_MILLI = 200f
        const val PHOTO_GREY = 0xFF808080.toInt()
        const val PNG_QUALITY = 100
        const val DEFAULT_FRAMES = 150
        const val COLS = 160
        const val ROWS = 120
        const val STEP = 4
        const val IMG_W = COLS * STEP
        const val IMG_H = ROWS * STEP
    }
}
