package com.sempermechanics.semper.data.examples

import android.content.ContentResolver
import android.content.ContentValues
import android.content.res.AssetManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.io.InputStream

/**
 * Save to phone: copies a bundled set into `Download/Semper examples/<folder>/`
 * so the student can pick it like their own photos. The set's README and
 * credit (and its licence text, for a BSD set) always go with it: both
 * licences ask that the notice travels with every copy.
 *
 * Writes go through a [Sink] so a test can stand in for MediaStore. Every
 * file is written pending and published once whole; on a failure or a
 * cancel the pending ones are removed, then the cancel goes on (ADR-018).
 */
class ExampleDownloads(private val assets: AssetManager, private val sink: Sink) {

    /** What one save came to. */
    sealed interface Outcome {
        /** [written] files copied into [folder]; [skipped] were there already. */
        data class Saved(val folder: String, val written: Int, val skipped: Int) : Outcome

        /** Storage is full, or the files could not be written. */
        data class Failed(val reason: String) : Outcome
    }

    /** Where the files go. [MediaStoreSink] on a phone. */
    interface Sink {
        /** True when [name] of [size] bytes is already in [relativePath] (an earlier save). */
        fun exists(relativePath: String, name: String, size: Long): Boolean

        /** A new pending file to write; null when it cannot be made. */
        fun create(relativePath: String, name: String, mimeType: String): Uri?

        fun write(uri: Uri, source: InputStream)

        fun publish(uri: Uri)

        fun discard(uri: Uri)
    }

    /**
     * Copies [set]'s files, calling [progress] with the number done after
     * each one. Files already saved with the same name and size are skipped,
     * so a second save only fills in what is missing.
     */
    suspend fun save(set: ExampleManifest, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Outcome {
        val relativePath = relativePathOf(set)
        val files = set.files
        val pending = mutableListOf<Uri>()
        var written = 0
        var skipped = 0
        try {
            files.forEachIndexed { index, name ->
                currentCoroutineContext().ensureActive()
                val asset = ExampleCatalog.path(set.id, name)
                val size = sizeOf(asset)
                if (sink.exists(relativePath, name, size)) {
                    skipped++
                } else {
                    val uri = sink.create(relativePath, name, ExampleAssetProvider.mimeTypeOf(name))
                        ?: throw IOException("Could not create $name")
                    pending += uri
                    assets.open(asset).use { sink.write(uri, it) }
                    sink.publish(uri)
                    pending -= uri
                    written++
                }
                progress(index + 1, files.size)
            }
            return Outcome.Saved(relativePath, written, skipped)
        } catch (e: IOException) {
            Timber.w(e, "Saving example %s failed", set.id)
            return Outcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            if (pending.isNotEmpty()) withContext(NonCancellable) { pending.forEach(sink::discard) }
        }
    }

    /** An asset's length: from its descriptor when stored uncompressed, else by reading it through. */
    private fun sizeOf(asset: String): Long = try {
        assets.openFd(asset).use { it.length }
    } catch (e: IOException) {
        Timber.d(e, "%s is compressed; counting it", asset)
        assets.open(asset).use { stream -> stream.skip(Long.MAX_VALUE) }
    }

    companion object {
        /** The folder under Downloads every set is saved in. */
        const val PARENT_FOLDER = "Semper examples"

        fun relativePathOf(set: ExampleManifest): String =
            "${Environment.DIRECTORY_DOWNLOADS}/$PARENT_FOLDER/${set.folderName}/"
    }
}

/** [ExampleDownloads.Sink] on the shared Downloads collection; no permission needed from Android 10 (minSdk 30). */
class MediaStoreSink(private val resolver: ContentResolver) : ExampleDownloads.Sink {

    private val collection: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun exists(relativePath: String, name: String, size: Long): Boolean {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}=?"
        val args = arrayOf(relativePath, name, size.toString())
        return resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
            ?.use { it.count > 0 } ?: false
    }

    override fun create(relativePath: String, name: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values)
    }

    override fun write(uri: Uri, source: InputStream) {
        val out = resolver.openOutputStream(uri) ?: throw IOException("No output stream for $uri")
        out.use { source.copyTo(it) }
    }

    override fun publish(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    override fun discard(uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (e: SecurityException) {
            Timber.w(e, "Could not remove pending %s", uri)
        }
    }
}
