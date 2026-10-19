package com.sempermechanics.semper.ui.analysis.frames

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.imaging.BitmapDecoder
import com.sempermechanics.semper.ui.analysis.wizard.ReferencePreviewLoader
import com.sempermechanics.semper.ui.common.media.displayNameOf
import java.io.InputStream

/**
 * A reference image as the engine is handed it: a RAW's RGBA, or the picked
 * file's own bytes, with its true pixel size and a display-size preview.
 */
internal class DecodedReference(val bytes: ByteArray, val size: ImageSize, val preview: Bitmap?)

/** True for a file name the RAW decoder reads (`.dng`, `.raw`). */
internal fun isRawName(name: String): Boolean = name.endsWith(".dng", true) || name.endsWith(".raw", true)

/**
 * Decodes [stream] as a reference, or null when it cannot be: a RAW to an
 * RGBA blob, anything else through the native decoder, which applies EXIF as
 * the engine does, so the size is the one a run sees. The wizard's reference
 * pick and Home's Re-run of an older analysis both come through here.
 */
@WorkerThread
internal suspend fun decodeReference(stream: InputStream, isRaw: Boolean): DecodedReference? {
    if (isRaw) {
        return BitmapDecoder.rgbaAndPreviewFromStream(stream)?.let { decoded ->
            DecodedReference(decoded.rgba, ImageSize(decoded.width, decoded.height), decoded.preview)
        }
    }
    val bytes = stream.readBytes()
    // Sizes from the native decoder, which applies EXIF as the engine does.
    val loaded = ReferencePreviewLoader.load(
        ReferencePreviewLoader.Request(bytes, 0, 0, BitmapDecoder.PREVIEW_MAX_EDGE),
    )
    return DecodedReference(bytes, ImageSize(loaded.width, loaded.height), loaded.bitmap)
        .takeIf { loaded.width > 0 && loaded.height > 0 }
}

/** A picked reference: its display name and [decodeReference] of it. Null when it cannot be read or decoded. */
@WorkerThread
internal suspend fun decodePickedReference(resolver: ContentResolver, uri: Uri): Pair<String, DecodedReference>? {
    val name = displayNameOf(resolver, uri)
    val decoded = resolver.openInputStream(uri)?.use { decodeReference(it, isRawName(name)) } ?: return null
    return name to decoded
}
