package com.indicvision.semper.util

import java.io.File

/**
 * Writes [dest] the [AtomicFiles] way: [write] fills [tmp], and only once it
 * returns is [tmp] promoted onto [dest] ([AtomicFiles.promote]: a rename,
 * else copy and delete). A reader never sees a half-written [dest].
 *
 * If [write] or the promote throws, [tmp] is deleted and the throwable
 * (cancellation included) is rethrown untouched, so no failed write leaves a
 * sidecar behind for the next run to mistake for a finished one.
 *
 * [tmp] defaults to [AtomicFiles.partOf]; a caller whose sidecar has another
 * established name (`index.json.tmp`, `Session.zip.tmp`, `<part>.tmp`) passes
 * it, because cleanup code elsewhere deletes the sidecar by that name.
 * [clearDest] deletes [dest] just before the promote, for the callers that did
 * so by hand; leave it off where [dest] must never be missing (the session
 * index relies on the rename replacing it in one step).
 *
 * Not for resumable downloads: those keep their `.part` across attempts on
 * purpose ([com.indicvision.semper.data.net.DriveTransfer]).
 *
 * @return what [write] returned (a digest, a count, …).
 */
@Suppress("TooGenericExceptionCaught") // cleans up on every throwable, then rethrows it unchanged
inline fun <T> AtomicFiles.writeVia(
    dest: File,
    tmp: File = partOf(dest),
    clearDest: Boolean = false,
    write: (tmp: File) -> T,
): T {
    try {
        val result = write(tmp)
        if (clearDest) dest.delete()
        promote(tmp, dest)
        return result
    } catch (e: Throwable) {
        tmp.delete()
        throw e
    }
}
