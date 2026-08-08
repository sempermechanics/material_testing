package com.indicvision.semper.ui.analysis

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** How multi-picked deformed frames are ordered for analysis. */
enum class FrameOrderMode {
    /** System picker / import order. */
    PICKER,

    /** Alphabetical by display name. */
    NAME,

    /** Capture / creation / modified time. */
    DATE,

    /** User drag order. */
    MANUAL,
}

/** Sort direction for [FrameOrderMode.NAME] and [FrameOrderMode.DATE]. */
enum class FrameOrderDirection {
    ASCENDING,
    DESCENDING,
}

/**
 * Resolves display names and best-effort creation times for content URIs, and
 * reorders parallel frame lists for the analysis wizard.
 */
object FrameOrderHelper {

    /** Anything below this is a seconds-based column, not milliseconds (year ~2286 in ms). */
    private const val MAX_EPOCH_SECONDS = 10_000_000_000L
    private const val MS_PER_SECOND = 1000L

    data class UriMeta(val uri: Uri, val name: String, val dateMs: Long)

    fun loadMeta(context: Context, uris: List<Uri>, displayName: (Uri) -> String): List<UriMeta> =
        uris.map { uri ->
            UriMeta(
                uri = uri,
                name = displayName(uri).ifBlank { "Image" },
                dateMs = resolveDateMs(context, uri),
            )
        }

    fun sortMeta(
        meta: List<UriMeta>,
        mode: FrameOrderMode,
        direction: FrameOrderDirection = FrameOrderDirection.ASCENDING,
    ): List<UriMeta> {
        val ascending = direction == FrameOrderDirection.ASCENDING
        return when (mode) {
            FrameOrderMode.PICKER, FrameOrderMode.MANUAL -> meta
            FrameOrderMode.NAME -> {
                val sorted = meta.sortedWith(
                    compareBy<UriMeta> { it.name.lowercase(Locale.US) }.thenBy { it.uri.toString() },
                )
                if (ascending) sorted else sorted.asReversed()
            }
            FrameOrderMode.DATE -> {
                val sorted = meta.sortedWith(
                    compareBy<UriMeta> { it.dateMs }.thenBy { it.name.lowercase(Locale.US) },
                )
                if (ascending) sorted else sorted.asReversed()
            }
        }
    }

    data class OrderedBatch(
        val paths: List<String>,
        val names: List<String>,
        val dates: List<Long>,
        val sizes: Map<String, Pair<Int, Int>>,
    )

    /**
     * Reorder parallel lists by [mode]. For MANUAL, [manualOrder] is the desired
     * permutation of indices into the current lists; ignored otherwise.
     * [direction] applies to NAME and DATE only.
     */
    @Suppress("LongParameterList") // the parallel frame lists plus the order they are put in
    fun reorder(
        paths: List<String>,
        names: List<String>,
        dates: List<Long>,
        sizes: Map<String, Pair<Int, Int>>,
        mode: FrameOrderMode,
        direction: FrameOrderDirection = FrameOrderDirection.ASCENDING,
        manualOrder: List<Int>? = null,
    ): OrderedBatch {
        val n = paths.size
        if (n == 0) {
            return OrderedBatch(emptyList(), emptyList(), emptyList(), emptyMap())
        }
        val ascending = direction == FrameOrderDirection.ASCENDING
        val indices = when (mode) {
            FrameOrderMode.PICKER -> paths.indices.toList()
            FrameOrderMode.NAME -> {
                val sorted = names.indices.sortedWith(
                    compareBy<Int> { names[it].lowercase(Locale.US) }.thenBy { it },
                )
                if (ascending) sorted else sorted.asReversed()
            }
            FrameOrderMode.DATE -> {
                val d = if (dates.size == n) dates else List(n) { Long.MAX_VALUE }
                val sorted = names.indices.sortedWith(
                    compareBy<Int> { d[it] }.thenBy { names[it].lowercase(Locale.US) },
                )
                if (ascending) sorted else sorted.asReversed()
            }
            FrameOrderMode.MANUAL -> {
                manualOrder?.takeIf { it.size == n } ?: paths.indices.toList()
            }
        }
        val newPaths = indices.map { paths[it] }
        return OrderedBatch(
            paths = newPaths,
            names = indices.map { names.getOrElse(it) { paths[it].substringAfterLast('/') } },
            dates = indices.map { dates.getOrElse(it) { Long.MAX_VALUE } },
            sizes = sizes.filterKeys { it in newPaths.toSet() },
        )
    }

    /**
     * Rename temp files to `%04d_…` matching [paths] order so lexicographic
     * path sort matches analysis order. Returns updated paths (and remapped sizes).
     */
    @Suppress("ReturnCount") // two nothing-to-do exits before the rename pass
    fun reprefixTempFiles(
        paths: List<String>,
        names: List<String>,
        sizes: Map<String, Pair<Int, Int>>,
    ): Pair<List<String>, Map<String, Pair<Int, Int>>> {
        if (paths.isEmpty()) return emptyList<String>() to emptyMap()
        val parent = File(paths.first()).parentFile ?: return paths to sizes
        val staging = paths.mapIndexed { index, oldPath ->
            val old = File(oldPath)
            val base = names.getOrElse(index) { old.name }
                .replace(Regex("^\\d{4}_"), "")
                .replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val staged = File(parent, String.format(Locale.US, "_ord_%04d_%s", index, base))
            if (old.absolutePath != staged.absolutePath) {
                if (staged.exists()) staged.delete()
                old.renameTo(staged)
            }
            staged
        }
        val newPaths = mutableListOf<String>()
        val newSizes = mutableMapOf<String, Pair<Int, Int>>()
        staging.forEachIndexed { index, staged ->
            val base = staged.name.removePrefix(String.format(Locale.US, "_ord_%04d_", index))
            val final = File(parent, String.format(Locale.US, "%04d_%s", index, base))
            if (staged.absolutePath != final.absolutePath) {
                if (final.exists()) final.delete()
                staged.renameTo(final)
            }
            newPaths.add(final.absolutePath)
            sizes[paths[index]]?.let { newSizes[final.absolutePath] = it }
                ?: sizes[staged.absolutePath]?.let { newSizes[final.absolutePath] = it }
        }
        return newPaths to newSizes
    }

    /** Best-effort capture/creation time; [Long.MAX_VALUE] when unknown (sorts last). */
    fun resolveDateMs(context: Context, uri: Uri): Long = sequenceOf(
        { queryMediaDate(context, uri) },
        { queryDocumentModified(context, uri) },
        { exifDate(context, uri) },
    ).mapNotNull { source -> source()?.takeIf { it > 0L } }
        .firstOrNull() ?: Long.MAX_VALUE

    /**
     * Date for an already-imported frame file. Used when sort-by-date is chosen
     * after a fast import that skipped URI EXIF probes.
     */
    fun resolveDateMs(file: File): Long = runCatching {
        val exif = ExifInterface(file.absolutePath)
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return@runCatching null
        val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        fmt.parse(raw)?.time
    }.getOrNull()?.takeIf { it > 0L }
        ?: file.lastModified().takeIf { it > 0L }
        ?: Long.MAX_VALUE

    /** First column that carries a usable time, in descending order of trust. */
    private fun queryMediaDate(context: Context, uri: Uri): Long? {
        val columns = buildList {
            add(MediaStore.MediaColumns.DATE_TAKEN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { add(MediaStore.PickerMediaColumns.DATE_TAKEN) }
            }
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
        }
        return runCatching {
            context.contentResolver.query(uri, columns.toTypedArray(), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    columns.firstNotNullOfOrNull { name -> columnMs(c, name).takeIf { it > 0L } }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /** DATE_TAKEN is milliseconds; DATE_ADDED / DATE_MODIFIED are seconds. 0 when absent. */
    private fun columnMs(cursor: Cursor, column: String): Long {
        val index = cursor.getColumnIndex(column)
        if (index < 0) return 0L
        val value = cursor.getLong(index)
        return when {
            value <= 0L -> 0L
            value < MAX_EPOCH_SECONDS -> value * MS_PER_SECOND
            else -> value
        }
    }

    private fun queryDocumentModified(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val i = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (i < 0) return@use null
            c.getLong(i).takeIf { it > 0L }
        }
    }.getOrNull()

    private fun exifDate(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                ?: return@use null
            val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            fmt.parse(raw)?.time
        }
    }.getOrNull()
}
