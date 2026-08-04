package com.indicvision.semper.data.net

import com.indicvision.semper.util.Digests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Drive resumable chunks must be 256 KiB multiples (except the final one). */
private const val MIN_CHUNK_BYTES = 256 * 1024

/** Upper bound on the per-chunk buffer allocation, whatever the server says. */
private const val MAX_CHUNK_BYTES = 32 * 1024 * 1024

/** How many times a truncated download may resume from the last byte. */
private const val DOWNLOAD_MAX_ATTEMPTS = 5

/** Copy buffer for proxied restore downloads. */
private const val DOWNLOAD_COPY_BUFFER = 1 shl 16

/**
 * Direct-to-Drive byte transfer: resumable upload / probe and attested download.
 * Owned by [IndicApi]; not a public entry point.
 */
internal class DriveTransfer(
    private val client: OkHttpClient,
    private val downloadClient: OkHttpClient,
    private val octet: MediaType,
) {

    /**
     * Resumable upload of [file] to a Drive [uploadUrl], in [chunkSize] chunks
     * (multiple of 256 KiB). Resumes from the server offset on reconnect. Bytes
     * go straight to Drive — not through the backend.
     *
     * Returns (driveFileId, md5Hex) where md5 is always the local MD5 of [file]
     * (Drive's completion JSON often omits `md5Checksum` under API v3 partial
     * responses; the backend requires a matching client md5 at `:complete`).
     */
    suspend fun uploadResumable(
        uploadUrl: String,
        file: File,
        chunkSize: Int,
        onBytes: (Long) -> Unit = {},
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val total = file.length()
        // The buffer is allocated at chunk size — clamp what the server
        // sent so a misconfigured value can never OOM the app. Drive needs
        // chunks in 256 KiB multiples (except the last).
        val chunk = chunkSize.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)

        // Where does Drive want us to continue — or does it already have the
        // whole file? A file fully uploaded in a prior attempt (but whose
        // completeFile never ran) reports COMPLETE here; return its id with
        // a local md5 instead of trying to re-send zero bytes and failing.
        val probe = probeStatus(uploadUrl, total)
        probe.result?.let { (driveId, driveMd5) ->
            return@withContext driveId to (driveMd5 ?: Digests.md5Hex(file))
        }
        var offset = probe.offset

        val digest = Digests.md5()
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(chunk)
            // Prefix already on Drive must be hashed so the digest covers the
            // whole file, not only the bytes we send on this resume.
            if (offset > 0L) {
                hashRange(raf, buf, 0L, offset, digest)
            }
            while (offset < total) {
                raf.seek(offset)
                val n = raf.read(buf, 0, minOf(chunk.toLong(), total - offset).toInt())
                if (n <= 0) throw IOException("unexpected EOF at $offset/$total")
                digest.update(buf, 0, n)
                val end = offset + n - 1
                val req = Request.Builder().url(uploadUrl)
                    .header("Content-Range", "bytes $offset-$end/$total")
                    .put(buf.toRequestBody(octet, 0, n)).build()
                client.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        HttpStatus.RESUME_INCOMPLETE -> {
                            offset = end + 1
                            onBytes(n.toLong())
                        }
                        HttpStatus.OK, HttpStatus.CREATED -> {
                            onBytes(n.toLong())
                            val (driveId, _) = IndicApiHttp.parseDriveResult(resp.body.string())
                            return@withContext driveId to Digests.toHex(digest.digest())
                        }
                        else -> throw IndicApi.ApiException(resp.code, resp.body.string())
                    }
                }
            }
        }

        // Loop reached `total` without a final 200/201 — the last bytes were
        // already on Drive from a previous attempt. Re-probe to finalize and
        // get the resource, rather than failing.
        val finalized = probeStatus(uploadUrl, total).result
            ?: throw IOException("upload finished without a final Drive response")
        // Digest already covers the whole file from the prefix+chunk updates.
        finalized.first to Digests.toHex(digest.digest())
    }

    /** Hash [start, end) of [raf] into [digest] using [buf] as a scratch buffer. */
    private fun hashRange(
        raf: RandomAccessFile,
        buf: ByteArray,
        start: Long,
        end: Long,
        digest: MessageDigest,
    ) {
        var pos = start
        while (pos < end) {
            raf.seek(pos)
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
            if (n <= 0) throw IOException("unexpected EOF hashing $pos/$end")
            digest.update(buf, 0, n)
            pos += n
        }
    }

    /** Current state of a resumable session: continue at [offset], or already [result]. */
    private data class UploadProbe(val offset: Long, val result: Pair<String, String?>?)

    /** Ask Drive what it already has: PUT `bytes * /total` with an empty body. */
    private fun probeStatus(uploadUrl: String, total: Long): UploadProbe {
        val req = Request.Builder().url(uploadUrl)
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(octet)).build()
        client.newCall(req).execute().use { resp ->
            return when (resp.code) {
                // Resume Incomplete: Range tells us the last byte received (may be absent = nothing yet).
                HttpStatus.RESUME_INCOMPLETE ->
                    UploadProbe(resp.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L, null)
                // Already complete — the body is the Drive file resource.
                HttpStatus.OK, HttpStatus.CREATED -> UploadProbe(
                    total,
                    IndicApiHttp.parseDriveResult(resp.body.string()),
                )
                // 404/410 = session expired; start fresh (caller re-inits on retry).
                else -> UploadProbe(0L, null)
            }
        }
    }

    /**
     * GET /v1/files/{id}/content — stream a file back from Drive into [dest].
     * These bytes are proxied by the backend (Drive has no anonymous download),
     * so this is the one path where the backend touches file content.
     *
     * Device-attested like writes: [signedGetHeaders] supplies a fresh nonce +
     * ECDSA over method/path/empty body per attempt. `Range` is an unsigned
     * header (not part of the signed message) so resume offsets can change
     * without rehashing the body.
     *
     * Writes to a sibling `.part` file and renames on success. If the transfer
     * drops mid-stream, retries with `Range: bytes=N-` so already-received
     * bytes are kept (backend forwards Range to Drive and returns 206).
     */
    @Suppress("CyclomaticComplexMethod") // one branch per HTTP status × resume/retry outcome
    suspend fun downloadFile(
        fileId: String,
        dest: File,
        baseUrl: String,
        signedGetHeaders: (path: String) -> Headers,
    ) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, "${dest.name}.part")
        val path = "/v1/files/$fileId/content"
        var attempt = 0
        while (true) {
            attempt++
            val offset = if (part.exists()) part.length() else 0L
            try {
                // Fresh challenge per attempt so a resumed Range request never
                // replays a consumed nonce.
                val headers = signedGetHeaders(path)
                val builder = Request.Builder()
                    .url("$baseUrl$path")
                    .headers(headers)
                    .get()
                if (offset > 0L) builder.header("Range", "bytes=$offset-")
                downloadClient.newCall(builder.build()).execute().use { resp ->
                    when (resp.code) {
                        HttpStatus.OK, HttpStatus.PARTIAL_CONTENT -> {
                            // 200 = full body (fresh start / proxy ignored Range) → overwrite;
                            // 206 = partial → append to the bytes already on disk.
                            val append = resp.code == HttpStatus.PARTIAL_CONTENT
                            java.io.FileOutputStream(part, append).use { out ->
                                resp.body.byteStream().use { input -> input.copyTo(out, DOWNLOAD_COPY_BUFFER) }
                            }
                        }
                        HttpStatus.RANGE_NOT_SATISFIABLE -> {
                            // Stale offset (partial longer than the object). Restart once.
                            if (offset > 0L && attempt < DOWNLOAD_MAX_ATTEMPTS) {
                                part.delete()
                                throw IOException("range_not_satisfiable; restarting $fileId")
                            }
                            throw IndicApi.ApiException(resp.code, IndicApiHttp.bodyText(resp))
                        }
                        else -> throw IndicApi.ApiException(resp.code, IndicApiHttp.bodyText(resp))
                    }
                }
                if (dest.exists() && !dest.delete()) {
                    Timber.w("Could not replace existing download target %s", dest)
                }
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                return@withContext
            } catch (e: IndicApi.ApiException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= DOWNLOAD_MAX_ATTEMPTS) throw e
                Timber.w(
                    e,
                    "download %s interrupted at %d bytes (attempt %d); resuming",
                    fileId,
                    if (part.exists()) part.length() else 0L,
                    attempt,
                )
            }
        }
    }
}
