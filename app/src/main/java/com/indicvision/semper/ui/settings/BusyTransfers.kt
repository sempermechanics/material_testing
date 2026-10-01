package com.indicvision.semper.ui.settings

import androidx.work.WorkInfo
import com.indicvision.semper.data.CloudRestore

/**
 * Which analyses on the settings page have a restore or a Save-to-Files
 * download in flight, keyed by [AnalysisEntry.downloadKey] (the cloud id).
 *
 * Answered from the WorkInfo lists Settings already observes for the
 * `restore` and [CloudRestore.TAG_BUNDLE_DOWNLOAD] tags, never by asking
 * WorkManager: its `getWorkInfosForUniqueWork(…).get()` blocks on a database
 * read, and Settings ran it on the main thread on every row tap and twice per
 * busy row on every WorkInfo change.
 *
 * A row is busy while its work is unfinished, and from the tap that started it
 * until the list first reports it ([mark]): WorkManager enqueues
 * asynchronously, so the job can be missing from the next emission. Main
 * thread only, like the observers that feed it.
 */
internal class BusyTransfers {

    private var restoring: Set<String> = emptySet()
    private var downloading: Set<String> = emptySet()
    private val marked = mutableSetOf<String>()

    /** The latest `restore` tag list. */
    fun onRestoreWork(infos: List<WorkInfo>) {
        restoring = unfinishedIds(infos, RESTORE_TAG)
    }

    /** The latest Save-to-Files tag list. */
    fun onDownloadWork(infos: List<WorkInfo>) {
        downloading = unfinishedIds(infos, CloudRestore.TAG_BUNDLE_DOWNLOAD)
    }

    fun isRestoring(key: String): Boolean = key in restoring

    fun isDownloading(key: String): Boolean = key in downloading

    fun isBusy(key: String): Boolean = key in marked || isRestoring(key) || isDownloading(key)

    /** A transfer for [key] was just started (or found already running) from this screen. */
    fun mark(key: String) {
        marked += key
    }

    /** Forget marks whose work the lists no longer report as unfinished. */
    fun settle() {
        marked.retainAll(restoring + downloading)
    }

    /** Every busy key, for the row adapter. */
    fun keys(): Set<String> = marked + restoring + downloading

    internal companion object {
        private const val RESTORE_TAG = "restore"

        /**
         * Cloud ids among [infos] whose work is not finished, read from the
         * per-session `<tag>-<cloudId>` tag each request carries next to [tag].
         */
        fun unfinishedIds(infos: List<WorkInfo>, tag: String): Set<String> {
            val prefix = "$tag-"
            return infos.asSequence()
                .filter { !it.state.isFinished }
                .mapNotNull { info -> info.tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix) }
                .filter { it.isNotBlank() }
                .toSet()
        }
    }
}
