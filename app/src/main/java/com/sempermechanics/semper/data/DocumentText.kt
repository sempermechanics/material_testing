package com.indicvision.semper.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.nio.charset.Charset

/**
 * Small text documents picked through SAF (a machine's CSV export). Call off
 * the main thread.
 */
object DocumentText {

    /** The provider's display name, or the last path segment when it has none. */
    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                    }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "document"
    }

    /**
     * The document as text, or null when it is longer than [maxBytes]. UTF-8
     * unless the bytes are not valid UTF-8, in which case ISO-8859-1 — the
     * other encoding a testing machine on Windows will have used.
     *
     * @throws IOException when the provider cannot open or read it.
     */
    @Throws(IOException::class)
    fun read(context: Context, uri: Uri, maxBytes: Int): String? {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("No stream for $uri")
        val bytes = stream.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK)
            var total = 0
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > maxBytes) return null
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        }
        return decode(bytes)
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
        return runCatching { utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, Charset.forName("ISO-8859-1")) }
    }

    private const val CHUNK = 64 * 1024
}
