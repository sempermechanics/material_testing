// Frame import: literal buffer/quality constants read clearest inline.

package com.indicvision.semper.ui.analysis

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.imaging.BitmapDecode
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Result of importing a deformed-frame batch into cacheDir/temp_deformed. */
data class ImportedBatch(
    val filePaths: List<String>,
    val originalNames: List<String>,
    val frameSizes: Map<String, Pair<Int, Int>>,
    val fromVideo: Boolean = false,
)

/**
 * URI → temp-file import for deformed frames. Call [importDeformedUris] off the
 * main thread; dimension probes use bounds-only decode when possible, with a
 * JNI fallback on the pinned native dispatcher.
 */
object FrameImportHelper {

    /** Cap [uris] to [maxFrames]; return the capped list (caller shows toast if truncated). */
    fun cappedUris(uris: List<Uri>, maxFrames: Int): List<Uri> = uris.take(maxFrames)

    /**
     * Writes deformed frames into cacheDir/temp_deformed (clears dir first).
     * Must be called off the main thread.
     * Returns [ImportedBatch] or null if nothing imported.
     */
    suspend fun importDeformedUris(
        context: Context,
        uris: List<Uri>,
        cacheDir: File,
        displayName: (Uri) -> String,
    ): ImportedBatch? {
        val tempDir = File(cacheDir, "temp_deformed")
        if (!tempDir.exists()) tempDir.mkdirs()
        tempDir.listFiles()?.forEach { it.delete() }

        val filePaths = mutableListOf<String>()
        // Temp path → original picked filename, kept so exports can use the
        // user's real (default) names instead of the sanitized temp names.
        val originalByPath = mutableMapOf<String, String>()
        // Temp path → pixel size, so the reference-match check is free later.
        val sizeByPath = mutableMapOf<String, Pair<Int, Int>>()

        for ((index, uri) in uris.withIndex()) {
            val originalName = displayName(uri)
            val isRaw = originalName.endsWith(".dng", true) || originalName.endsWith(".raw", true)

            val sanitizedName = originalName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val filename = String.format(Locale.US, "%04d_%s", index, sanitizedName)
            val file = File(tempDir, filename)

            val frameSize: Pair<Int, Int>? = if (isRaw) {
                importRawUri(context, uri, file)
            } else {
                importStreamedUri(context, uri, file)
            }

            if (!file.exists() || file.length() == 0L) continue

            filePaths.add(file.absolutePath)
            originalByPath[file.absolutePath] = originalName
            frameSize?.let { sizeByPath[file.absolutePath] = it }
        }

        if (filePaths.isEmpty()) return null

        val sortedPaths = filePaths.sorted()
        return ImportedBatch(
            filePaths = sortedPaths,
            originalNames = sortedPaths.map { originalByPath[it] ?: File(it).name },
            frameSizes = sizeByPath,
            fromVideo = false,
        )
    }

    /**
     * RAW/DNG must become RGBA bytes for the engine — still buffered, but
     * without the unused first-frame preview decode.
     */
    private fun importRawUri(context: Context, uri: Uri, dest: File): Pair<Int, Int>? {
        var frameSize: Pair<Int, Int>? = null
        context.contentResolver.openInputStream(uri)?.use { stream ->
            frameSize = BitmapDecode.writeRgbaFromStream(stream, dest)
        }
        return frameSize
    }

    /** Stream URI → file without holding a full ByteArray; probe dims afterwards. */
    private suspend fun importStreamedUri(context: Context, uri: Uri, dest: File): Pair<Int, Int>? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        return probeImageSize(dest)
    }

    /**
     * Bounds-only decode when possible; JNI on file bytes if BitmapFactory
     * cannot read the container (rare formats the native stack still accepts).
     */
    private suspend fun probeImageSize(file: File): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            return opts.outWidth to opts.outHeight
        }
        return withContext(SemperNativeLib.nativeDispatcher) {
            runCatching {
                val dims = SemperNativeLib.getImageDimensions(file.readBytes())
                if (dims.size >= 2 && dims[0] > 0 && dims[1] > 0) dims[0] to dims[1] else null
            }.getOrNull()
        }
    }
}
