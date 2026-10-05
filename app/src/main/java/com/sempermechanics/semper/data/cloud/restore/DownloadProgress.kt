package com.sempermechanics.semper.data.cloud.restore

import androidx.work.Data
import com.sempermechanics.semper.navigation.IntentKeys

/**
 * The progress both download workers publish: the restore and the bundle
 * download. They carried byte-identical copies of this (FI-14).
 */
internal object DownloadProgress {
    private const val WHOLE = 100L

    /**
     * Whole percent of [total], or 0 while the size is not known yet. Long
     * arithmetic, because a Session.zip can pass 2 GB and `done * 100` would
     * overflow an Int well before that.
     */
    fun percent(done: Long, total: Long): Int =
        if (total > 0L) ((done.coerceAtLeast(0L) * WHOLE) / total).toInt().coerceIn(0, WHOLE.toInt()) else 0

    /** Progress [Data] for the UI; [localId] names the row a restore is filling. */
    fun data(done: Long, total: Long, localId: String? = null): Data =
        Data.Builder()
            .putString(IntentKeys.UPLOAD_PHASE, IntentKeys.PHASE_DOWNLOAD)
            .putInt(IntentKeys.UPLOAD_PERCENT, percent(done, total))
            .apply { if (localId != null) putString(IntentKeys.SESSION_LOCAL_ID, localId) }
            .build()
}
