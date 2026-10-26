package com.sempermechanics.semper.data.examples

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Serves the bundled example sets (ADR-031) as `content://` URIs, read only:
 * `content://<applicationId>.examples/<set id>/<file>`. Try it hands these to
 * the wizard as if they were picked, so no 50 MB copy runs first, the names
 * stay `pmma_01.png`, and the URIs still resolve after a process death.
 *
 * Only the files a set's `example.json` lists are served, and only for
 * reading. Not exported: only this app opens them. The assets they come from
 * are stored uncompressed (`noCompress` in `app/build.gradle.kts`), so each is
 * one seekable file descriptor into the APK.
 */
class ExampleAssetProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = uri.lastPathSegment?.let(::mimeTypeOf)

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val path = assetPath(uri).takeIf { mode == "r" }
            ?: throw FileNotFoundException("Not a readable example file: $uri ($mode)")
        return try {
            requireContext().assets.openFd(path)
        } catch (e: IOException) {
            throw FileNotFoundException("Unreadable example file $uri").apply { initCause(e) }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val size = assetPath(uri)?.let(::sizeOf) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> uri.lastPathSegment
                OpenableColumns.SIZE -> size
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    private fun sizeOf(path: String): Long? = try {
        requireContext().assets.openFd(path).use { it.length }
    } catch (e: IOException) {
        Timber.w(e, "Example file %s has no descriptor", path)
        null
    }

    /** The asset path [uri] names, when it is a file of a bundled set; null otherwise. */
    private fun assetPath(uri: Uri): String? {
        val segments = uri.pathSegments
        val valid = uri.authority == authority(requireContext()) && segments.size == 2
        val (id, file) = segments.takeIf { valid } ?: return null
        val set = ExampleCatalog.find(requireContext().assets, id)
        return ExampleCatalog.path(id, file).takeIf { set != null && file in set.files }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException(READ_ONLY)

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException(READ_ONLY)

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException(READ_ONLY)

    companion object {
        private const val READ_ONLY = "Example files are read only"

        fun authority(context: Context): String = "${context.packageName}.examples"

        /** The URI of [file] in the set [id]. */
        fun uriFor(context: Context, id: String, file: String): Uri = Uri.Builder()
            .scheme("content")
            .authority(authority(context))
            .appendPath(id)
            .appendPath(file)
            .build()

        /** The MIME type of an example file, from its extension. */
        fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "tif", "tiff" -> "image/tiff"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "md" -> "text/markdown"
            else -> "text/plain"
        }
    }
}
