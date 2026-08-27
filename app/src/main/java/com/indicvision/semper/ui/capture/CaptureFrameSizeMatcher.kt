package com.indicvision.semper.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.indicvision.semper.imaging.ImageEncode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Brings every deformed frame to the reference's exact pixel dimensions.
 *
 * The engine clamps its AKAZE search window to the reference size and reuses
 * it on every deformed frame, so a size mismatch makes the wizard refuse to
 * proceed.
 *
 * With a locked reference this is a no-op: it came off the same stream at the
 * same resolution, so every frame already matches and nothing is touched. It
 * still runs because the fallback path — the vendor Camera app's test shot as
 * the reference — does need it, and because a resample that silently degrades
 * every frame is worse than one the wizard can refuse.
 */
internal object CaptureFrameSizeMatcher {

    /** Aspect-ratio tolerance below which a source frame already matches. */
    private const val ASPECT_EPSILON = 0.01f

    private const val MIN_PARALLELISM = 2
    private const val MAX_PARALLELISM = 4

    /** Concurrent workers — bounded so full-res bitmaps (tens of MB each) held
     *  in flight don't exhaust memory on the low-RAM devices [CaptureBudget]
     *  already guards against. */
    private val PARALLELISM = Runtime.getRuntime().availableProcessors()
        .coerceIn(MIN_PARALLELISM, MAX_PARALLELISM)

    /**
     * Rescales (center-cropping first when the aspect ratio differs) every
     * frame in [paths] that doesn't already match [refFile] exactly. Decode,
     * crop, scale and PNG-encode are CPU-heavy per frame, hence [onProgress].
     */
    suspend fun matchToReference(
        refFile: File,
        paths: List<String>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<String> = coroutineScope {
        val refOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(refFile.absolutePath, refOpts)
        val refW = refOpts.outWidth
        val refH = refOpts.outHeight
        if (refW <= 0 || refH <= 0) return@coroutineScope paths
        val total = paths.size
        val done = AtomicInteger(0)
        val gate = Semaphore(PARALLELISM)
        paths.map { path ->
            async(Dispatchers.IO) {
                gate.withPermit { matchOneOrKeep(path, refW, refH) }.also {
                    onProgress(done.incrementAndGet(), total)
                }
            }
        }.awaitAll()
    }

    /**
     * Never throws. This runs after a capture run has already completed, so an
     * IOException here — out of space is the realistic one — would otherwise
     * escape `async`/`awaitAll` and crash the app holding the only copy of the
     * frames. Keeping the unmatched frame instead lets the wizard refuse it,
     * which is a failure the user can see and act on.
     */
    private fun matchOneOrKeep(path: String, refW: Int, refH: Int): String =
        runCatching { matchOne(path, refW, refH) }.getOrElse {
            Timber.w(it, "Could not match %s to the reference size", path)
            path
        }

    private fun matchOne(path: String, refW: Int, refH: Int): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val alreadyMatches = opts.outWidth == refW && opts.outHeight == refH
        val original = if (alreadyMatches) null else BitmapFactory.decodeFile(path)
        if (original != null) {
            val cropped = centerCropToAspect(original, refW, refH)
            val matched = if (cropped.width == refW && cropped.height == refH) {
                cropped
            } else {
                Bitmap.createScaledBitmap(cropped, refW, refH, true)
            }
            try {
                File(path).outputStream().use { out ->
                    matched.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, out)
                }
            } finally {
                original.recycle()
                if (cropped !== original && cropped !== matched) cropped.recycle()
                matched.recycle()
            }
        }
        return path
    }

    /**
     * Center-crops [bmp] to [targetW]:[targetH]'s aspect ratio, preserving the
     * speckle pattern's true proportions — a plain non-uniform stretch (e.g.
     * the locked session's YUV still catalogue landing on a different aspect
     * ratio than the vendor Camera app's test shot) would distort the geometry
     * DIC depends on.
     */
    private fun centerCropToAspect(bmp: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val targetAspect = targetW.toFloat() / targetH
        val srcAspect = bmp.width.toFloat() / bmp.height
        if (kotlin.math.abs(targetAspect - srcAspect) < ASPECT_EPSILON) return bmp
        return if (srcAspect > targetAspect) {
            val cropW = (bmp.height * targetAspect).toInt().coerceIn(1, bmp.width)
            val x = (bmp.width - cropW) / 2
            Bitmap.createBitmap(bmp, x, 0, cropW, bmp.height)
        } else {
            val cropH = (bmp.width / targetAspect).toInt().coerceIn(1, bmp.height)
            val y = (bmp.height - cropH) / 2
            Bitmap.createBitmap(bmp, 0, y, bmp.width, cropH)
        }
    }
}
