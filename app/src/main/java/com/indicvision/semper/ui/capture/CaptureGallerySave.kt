package com.indicvision.semper.ui.capture

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Puts a run's captured frames in the phone's own gallery, beside the copy the
 * app keeps for itself.
 *
 * The frames the app writes are lossless grey PNGs — they *are* the raw
 * measurement, and a user who wants to re-analyse a specimen next year, or
 * hand the frames to a colleague, should not have to go through this app to
 * reach them. The gallery is where a phone user already knows to look.
 *
 * **It never costs the run anything.** Every failure here — no room, a volume
 * that will not take an insert, an old Android with no scoped-storage path —
 * is logged and returns a count, never an exception and never a dialog that
 * stands between the user and their results. The frames are already safe in
 * the app's own storage; this is a second copy, and a second copy that fails
 * is not a lost measurement.
 */
internal object CaptureGallerySave {

    /** Album the frames land in, under the phone's Pictures directory. */
    const val ALBUM = "semper"

    /**
     * Free space that must remain *after* the copy.
     *
     * A gallery copy that fills the volume would take the next run down with
     * it, and the next run is worth more than this one's second copy. Half a
     * gigabyte is enough for a further capture to start and fail politely
     * rather than mid-write.
     */
    const val HEADROOM_BYTES = 512L * 1024 * 1024

    /**
     * What a save attempt did, so the caller can say one true sentence about
     * it rather than guessing.
     */
    sealed interface Outcome {
        /** [saved] of [total] frames reached the gallery. */
        data class Saved(val saved: Int, val total: Int) : Outcome

        /** Nothing was attempted: not enough room to copy [needBytes]. */
        data class NoRoom(val needBytes: Long) : Outcome

        /** Nothing was attempted: this Android cannot write there without a permission prompt. */
        data object Unsupported : Outcome
    }

    /**
     * Copy [files] into the gallery under [ALBUM].
     *
     * Below Android 10 there is no scoped-storage path into the gallery, and
     * the alternative is asking for `WRITE_EXTERNAL_STORAGE` — a broad,
     * scary-sounding permission prompt in the middle of a measurement, for a
     * convenience copy. The frames stay in app storage on those devices and
     * the user is told, which is the better trade.
     */
    suspend fun save(context: Context, files: List<File>): Outcome = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext Outcome.Unsupported
        val present = files.filter { it.exists() && it.length() > 0L }
        if (present.isEmpty()) return@withContext Outcome.Saved(0, 0)
        val need = present.sumOf { it.length() }
        // The same number the capture budget gate reads, so a run that was
        // allowed to record cannot be told there is no room to keep it.
        if (!hasRoom(CaptureResources.availStorageBytes(context), need)) {
            Timber.w("gallery save: %d bytes needed, not enough room", need)
            return@withContext Outcome.NoRoom(need)
        }
        val folder = folderName()
        var saved = 0
        for (file in present) {
            if (saveOne(context, file, folder)) saved++
        }
        Timber.i("gallery save: %d of %d frames into %s", saved, present.size, folder)
        Outcome.Saved(saved, present.size)
    }

    /** Whether [availableBytes] can take [needBytes] and still leave [HEADROOM_BYTES]. */
    internal fun hasRoom(availableBytes: Long, needBytes: Long): Boolean =
        availableBytes - needBytes >= HEADROOM_BYTES

    /**
     * One run per subfolder, named for when it was taken.
     *
     * Sixty frames of one specimen loose among a user's photos is not a
     * result, it is a mess; a dated folder is what makes the copy worth
     * having. The timestamp is taken once for the whole run so a save that
     * straddles a minute boundary does not split across two folders.
     */
    internal fun folderName(now: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        return "$ALBUM/$stamp"
    }

    private fun saveOne(context: Context, file: File, folder: String): Boolean = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeOf(file))
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$folder")
            // Hidden from the gallery until the bytes are all there, so a save
            // interrupted by the process dying leaves no half-frame that looks
            // like a real one.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        resolver.openOutputStream(uri).use { out ->
            if (out == null) {
                resolver.delete(uri, null, null)
                return@runCatching false
            }
            file.inputStream().use { it.copyTo(out) }
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        true
    }.onFailure { Timber.w(it, "gallery save: %s failed", file.name) }.getOrDefault(false)

    /** PNG unless the file says otherwise; the run's frames are always PNG. */
    internal fun mimeOf(file: File): String =
        if (file.name.endsWith(".jpg", ignoreCase = true)) "image/jpeg" else "image/png"
}
