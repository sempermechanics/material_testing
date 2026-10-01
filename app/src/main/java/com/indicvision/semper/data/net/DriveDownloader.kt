package com.indicvision.semper.data.net

import com.indicvision.semper.data.cloud.restore.RestoreDownloadOutcomes
import com.indicvision.semper.util.AtomicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Consecutive failures without byte progress before giving up. Reset whenever
 * a chunk lands so a large Session.zip is not capped at five total requests.
 */
private const val DOWNLOAD_MAX_ATTEMPTS = 8

/** Copy buffer for proxied restore downloads. */
private const val DOWNLOAD_COPY_BUFFER = 1 shl 16

/** Log/exception preview length for non-success download bodies. */
private const val DOWNLOAD_ERROR_BODY_PREVIEW = 120

/**
 * Bounded Range window for each proxied GET — never open-ended (the `/content`
 * route's Cloud Run + gateway deadline is 300s; see `backend/gateway/openapi.yaml`
 * and `--timeout=300` in the deploy workflow). Window size is *adaptive*, not
 * fixed, because the right size depends on the link, not the device:
 *
 * - A slow link must stay well under the 300s budget — a window sized for a
 *   fast connection would time out and lose all its bytes on a slow one.
 * - A fast link benefits from a bigger window: each window costs a fresh
 *   attestation challenge (Firestore read+write+delete + an audit write), so
 *   fewer, larger windows cut real Firestore-quota cost.
 * - Progress reporting is per-window ([onBytes] fires once a window lands), so
 *   an oversized window on a slow link also makes restore progress look stuck.
 *
 * [nextWindowBytes] adapts the size after every completed window toward
 * [TARGET_WINDOW_SECONDS] of transfer at the just-observed throughput, so a
 * slow link naturally stays small (frequent progress, cheap retries) and a
 * fast one grows toward [MAX_DOWNLOAD_WINDOW_BYTES] (fewer requests) — without
 * ever risking the gateway deadline.
 */
private const val MIN_DOWNLOAD_WINDOW_BYTES = 1 shl 20 // 1 MiB
private const val MAX_DOWNLOAD_WINDOW_BYTES = 16 shl 20 // 16 MiB — ~40s at the ~420 KB/s

// measured on-device rate; the 300s deadline still leaves a >7x margin at that rate.
private const val INITIAL_DOWNLOAD_WINDOW_BYTES = 4 shl 20 // 4 MiB — a mid-range starting

// guess so a fast link converges up and a slow one converges down within a couple windows.
private const val TARGET_WINDOW_SECONDS = 30.0 // ~10x margin under the 300s gateway deadline
private const val MILLIS_PER_SECOND = 1000.0

/**
 * Given the throughput observed on the just-completed window, pick the next
 * window size: `throughput * TARGET_WINDOW_SECONDS`, clamped to
 * [[MIN_DOWNLOAD_WINDOW_BYTES], [MAX_DOWNLOAD_WINDOW_BYTES]] and rounded down to
 * a power of two (Drive chunk-alignment friendly, and avoids oscillating on
 * small throughput jitter between adjacent non-power-of-two sizes).
 */
internal fun nextWindowBytes(bytesInWindow: Long, elapsedMs: Long): Int {
    if (bytesInWindow <= 0L || elapsedMs <= 0L) return INITIAL_DOWNLOAD_WINDOW_BYTES
    val throughputBytesPerSec = bytesInWindow * MILLIS_PER_SECOND / elapsedMs
    val target = (throughputBytesPerSec * TARGET_WINDOW_SECONDS)
        .coerceIn(MIN_DOWNLOAD_WINDOW_BYTES.toDouble(), MAX_DOWNLOAD_WINDOW_BYTES.toDouble())
    // Round down to a power of two >= MIN_DOWNLOAD_WINDOW_BYTES.
    var pow = MIN_DOWNLOAD_WINDOW_BYTES
    while (pow.toLong() * 2 <= target.toLong() && pow < MAX_DOWNLOAD_WINDOW_BYTES) pow *= 2
    return pow
}

/** The attested, windowed restore download behind [DriveTransfer.downloadFile]. */
internal class DriveDownloader(private val downloadClient: OkHttpClient) {

    /** Runs [download], signing each window's GET with [signedGetHeaders] ([DriveTransfer.downloadFile]). */
    suspend fun download(download: DriveDownload, signedGetHeaders: (path: String) -> Headers) =
        withContext(Dispatchers.IO) {
            download.dest.parentFile?.mkdirs()
            // Stale complete from a prior corrupt finalize — always rebuild.
            if (download.dest.exists()) download.dest.delete()
            while (true) {
                download.attempt++
                val offset = download.haveBytes()
                if (download.finishIfComplete(offset)) return@withContext
                try {
                    // Fresh challenge per window so a resumed Range never replays a nonce.
                    if (fetchWindow(download, offset, signedGetHeaders(download.path))) return@withContext
                } catch (e: IndicApi.ApiException) {
                    throw e
                } catch (e: IOException) {
                    if (download.attempt >= DOWNLOAD_MAX_ATTEMPTS) throw e
                    // Class name, not the exception: a file error's message is the
                    // local path, and WARN reaches Crashlytics.
                    Timber.w(
                        "download %s interrupted at %d bytes (attempt %d, %s); resuming",
                        download.fileId,
                        download.haveBytes(),
                        download.attempt,
                        e.javaClass.simpleName,
                    )
                }
            }
        }

    /** GETs the next window after [offset] bytes on disk. True when the download is done. */
    private suspend fun fetchWindow(d: DriveDownload, offset: Long, headers: Headers): Boolean {
        // `offset` is a position within the window; `remoteOffset` is the absolute
        // position in the remote object, which the Range header and the
        // Content-Range answer must agree on. They differ for a windowed fetch
        // (rangeStart > 0), where a window-relative Range asked for `bytes=0-…`.
        val remoteOffset = d.rangeStart + offset
        val windowEnd = if (d.expectedBytes > 0L) d.rangeStart + d.expectedBytes - 1 else Long.MAX_VALUE
        val end = minOf(remoteOffset + d.windowBytes - 1, windowEnd)
        val request = Request.Builder()
            .url(d.baseUrl + d.path)
            .headers(headers)
            // identity: OkHttp's default Accept-Encoding: gzip + Range
            // can corrupt binary zips (partial gzip windows inflate to
            // garbage → ZipException: invalid distance too far back).
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=$remoteOffset-$end")
            .get()
            .build()
        val startedMs = System.currentTimeMillis()
        downloadClient.newCall(request).execute().use { resp ->
            return when (resp.code) {
                HttpStatus.OK -> {
                    acceptFullBody(d, resp)
                    true
                }
                HttpStatus.PARTIAL_CONTENT -> appendPartial(d, resp, offset, startedMs)
                HttpStatus.RANGE_NOT_SATISFIABLE -> finishUnsatisfiableRange(d, resp, offset)
                else -> failWindow(d, resp)
            }
        }
    }

    /**
     * The proxy ignored Range and sent a full-body reply. It goes to a scratch
     * file first — a truncated 200 must not wipe a good partial `.part` — and
     * becomes the whole download once its length checks out.
     */
    private suspend fun acceptFullBody(d: DriveDownload, resp: Response) {
        val scratch = AtomicFiles.fullOf(d.dest)
        scratch.delete()
        FileOutputStream(scratch, false).use { out ->
            resp.body.byteStream().use { input -> copyWithProgress(input, out, d.onBytes) }
        }
        d.sliceToWindow(scratch)
        val got = scratch.length()
        val problem = when {
            d.expectedBytes > 0L && got != d.expectedBytes ->
                "truncated full-body download for ${d.fileId}: got $got, expected ${d.expectedBytes}"
            got <= 0L -> "empty full-body download for ${d.fileId}"
            else -> null
        }
        if (problem != null) {
            scratch.delete()
            throw IOException(problem)
        }
        d.part.delete()
        AtomicFiles.promote(scratch, d.part)
        d.promoteToDest()
        d.onBytes(d.dest.length())
    }

    /** Appends a 206 window to the `.part` file. True when that completed the download. */
    private suspend fun appendPartial(d: DriveDownload, resp: Response, offset: Long, startedMs: Long): Boolean {
        val range = d.contentRangeAt(resp, offset)
        // For a windowed fetch the object's total says nothing about the target
        // length; expectedBytes is authoritative.
        if (d.rangeStart == 0L) range.total?.let { d.reportedTotal = it }
        val wrote = d.appendWindow(resp, offset, range)
        d.attempt = 0
        // Size the next window from this one's throughput. A short or failed
        // window never gets here, so a stall doesn't shrink it on bad data.
        d.windowBytes = nextWindowBytes(wrote, System.currentTimeMillis() - startedMs)
        return d.finishIfComplete(d.part.length())
    }

    /** A 416: done if what is on disk is the whole target, else restart from zero (or give up). */
    private fun finishUnsatisfiableRange(d: DriveDownload, resp: Response, offset: Long): Boolean {
        if (d.isComplete(offset)) {
            d.promoteToDest()
            return true
        }
        if (offset > 0L && d.attempt < DOWNLOAD_MAX_ATTEMPTS) {
            d.part.delete()
            d.reportedTotal = -1L
            throw IOException("range_not_satisfiable; restarting ${d.fileId}")
        }
        throw IndicApiHttp.apiException(resp)
    }

    /**
     * Any other status. A refused client nonce and a transient proxy failure
     * are [IOException]s, which [download] resumes; the rest is final.
     */
    private fun failWindow(d: DriveDownload, resp: Response): Nothing {
        val body = IndicApiHttp.bodyText(resp)
        // Signed with a client nonce the server would not take: go back to
        // challenges and re-sign this window.
        val nonceRefused = ClientNonce.isRefusal(resp.code, body) && ClientNonce.usable()
        if (nonceRefused) ClientNonce.markRefused()
        val transient = RestoreDownloadOutcomes.shouldResumeAfterHttp(
            code = resp.code,
            attempt = d.attempt,
            maxAttempts = DOWNLOAD_MAX_ATTEMPTS,
        )
        throw when {
            nonceRefused -> IOException("client nonce refused downloading ${d.fileId}")
            transient -> IOException(
                "transient HTTP ${resp.code} downloading ${d.fileId}" +
                    body.take(DOWNLOAD_ERROR_BODY_PREVIEW).let { if (it.isNotBlank()) ": $it" else "" },
            )
            else -> IndicApi.ApiException(resp.code, body, IndicApiHttp.requestIdOf(resp))
        }
    }
}

/** One [DriveTransfer.downloadFile] call: what it fetches, where the bytes go, and how far it got. */
internal class DriveDownload(
    val fileId: String,
    val dest: File,
    val baseUrl: String,
    val expectedBytes: Long,
    val rangeStart: Long,
    val onBytes: suspend (haveBytes: Long) -> Unit,
) {
    init {
        require(rangeStart >= 0L) { "rangeStart must not be negative" }
        require(rangeStart == 0L || expectedBytes > 0L) {
            "a windowed download must declare its length"
        }
    }

    val path = "/v1/files/$fileId/content"
    val part: File = AtomicFiles.partOf(dest)
    var attempt = 0

    /** The object's size from a whole-object fetch's Content-Range, or -1. */
    var reportedTotal = -1L

    /**
     * Adapts toward the link's throughput after every completed window (see
     * [nextWindowBytes]). Kept across retries: a single transient failure
     * doesn't mean the link itself got slower.
     */
    var windowBytes = INITIAL_DOWNLOAD_WINDOW_BYTES

    fun haveBytes(): Long = if (part.exists()) part.length() else 0L

    fun isComplete(haveBytes: Long): Boolean =
        RestoreDownloadOutcomes.isComplete(haveBytes, expectedBytes, reportedTotal)

    /** Reports [haveBytes] and, when they are the whole target, promotes them. True when done. */
    suspend fun finishIfComplete(haveBytes: Long): Boolean {
        if (haveBytes > 0L) onBytes(haveBytes)
        if (!isComplete(haveBytes)) return false
        promoteToDest()
        onBytes(dest.length())
        return true
    }

    /** Renames the finished `.part` onto [dest]. */
    fun promoteToDest() {
        if (dest.exists() && !dest.delete()) {
            // Size, not the path: it names the user's files, and WARN reaches Crashlytics.
            Timber.w("Could not replace existing download target (%d B)", dest.length())
        }
        AtomicFiles.promote(part, dest)
    }

    /**
     * A proxy that ignores Range hands back the whole object. For a windowed
     * fetch that is still usable: slice the window out of [scratch] instead of
     * failing and retrying forever.
     */
    fun sliceToWindow(scratch: File) {
        val got = scratch.length()
        if (rangeStart == 0L && expectedBytes !in 1 until got) return
        if (got < rangeStart + expectedBytes) {
            scratch.delete()
            throw IOException(
                "full-body download for $fileId is $got B, too short for window $rangeStart+$expectedBytes",
            )
        }
        sliceInPlace(scratch, rangeStart, expectedBytes)
    }

    /**
     * The 206's Content-Range, which must start where we asked: appending a
     * window that starts elsewhere would splice the wrong bytes into the file.
     */
    fun contentRangeAt(resp: Response, offset: Long): RestoreDownloadOutcomes.ContentRange {
        val range = RestoreDownloadOutcomes.parseContentRange(resp.header("Content-Range"))
            ?: throw IOException("206 without Content-Range at offset $offset for $fileId")
        val remoteOffset = rangeStart + offset
        if (range.start != remoteOffset) {
            throw IOException("Content-Range start ${range.start} != offset $remoteOffset for $fileId")
        }
        return range
    }

    /**
     * Appends the 206 body after [offset] bytes and returns how many it wrote.
     * A body that is not exactly [range] long is rewound to [offset] and thrown.
     */
    fun appendWindow(resp: Response, offset: Long, range: RestoreDownloadOutcomes.ContentRange): Long {
        FileOutputStream(part, true).use { out ->
            resp.body.byteStream().use { input -> input.copyTo(out, DOWNLOAD_COPY_BUFFER) }
        }
        val wrote = part.length() - offset
        val expectedWrote = range.end - range.start + 1
        if (wrote == expectedWrote) return wrote
        if (wrote > 0L) RandomAccessFile(part, "rw").use { it.setLength(offset) }
        throw IOException(
            if (wrote <= 0L) {
                "empty 206 body at offset $offset for $fileId"
            } else {
                "short 206 for $fileId: wrote $wrote, Content-Range expected $expectedWrote"
            },
        )
    }
}

/**
 * Reduce [file] in place to the [length] bytes starting at [start] — the window a
 * Range-ignoring proxy forced us to download in full.
 */
private fun sliceInPlace(file: File, start: Long, length: Long) {
    RandomAccessFile(file, "rw").use { raf ->
        val buffer = ByteArray(DOWNLOAD_COPY_BUFFER)
        var read = start
        var write = 0L
        var remaining = length
        while (remaining > 0L) {
            raf.seek(read)
            val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n <= 0) break
            raf.seek(write)
            raf.write(buffer, 0, n)
            read += n
            write += n
            remaining -= n
        }
        raf.setLength(write)
    }
}

/** Copy [input] → [out], reporting cumulative bytes via [onBytes] each buffer. */
private suspend fun copyWithProgress(
    input: InputStream,
    out: OutputStream,
    onBytes: suspend (haveBytes: Long) -> Unit,
) {
    val buf = ByteArray(DOWNLOAD_COPY_BUFFER)
    var have = 0L
    var lastReport = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        have += n
        if (have - lastReport >= DOWNLOAD_COPY_BUFFER) {
            lastReport = have
            onBytes(have)
        }
    }
    onBytes(have)
}
