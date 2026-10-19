package com.sempermechanics.semper.data.cloud.restore

import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.HttpFailure

/**
 * Why a restore or a Save-to-Files download failed, as both download workers
 * decide what to do about it: give up with a reason, or retry.
 *
 * Classify after cancellation has been dealt with; a
 * [kotlinx.coroutines.CancellationException] is not a failure.
 */
internal sealed interface DownloadFailure {
    val cause: Exception

    /** The backend answered 404 (the backup is gone) or 403 (not this account's). Retrying can't fix either. */
    data class Rejected(override val cause: ApiException) : DownloadFailure

    /**
     * The restore kill switch is on (503 `feature_paused`): not a failure of
     * this backup. The work ends saying restoring is paused, without counting
     * a failure; the user tries again once it lifts.
     */
    data class Paused(override val cause: ApiException) : DownloadFailure

    /**
     * The backup cannot be downloaded as it is: corrupt bytes ([corrupt]), or no
     * file the transfer needs ([UnrestorableBackupException]). Retrying can't fix it.
     */
    data class Unusable(override val cause: Exception, val corrupt: Boolean) : DownloadFailure

    /**
     * Anything else: a dropped connection, a 5xx or 429 the download could not
     * ride out, a missing sign-in, a full disk. A later attempt may go through.
     */
    data class Transient(override val cause: Exception) : DownloadFailure {
        /** The backend's answer, when there was one. */
        val api: ApiException? get() = cause as? ApiException
    }

    companion object {
        fun of(e: Exception): DownloadFailure = when {
            e is ApiException && ApiErrors.hasCode(e.body, ApiErrors.FEATURE_PAUSED) -> Paused(e)
            e is ApiException -> if (HttpFailure.classify(e).isGoneOrNotOurs) Rejected(e) else Transient(e)
            RestoreDownloadOutcomes.isTerminalFailure(e) ->
                Unusable(e, corrupt = RestoreDownloadOutcomes.isTerminalCorruptFailure(e))
            else -> Transient(e)
        }
    }
}
