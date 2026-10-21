package com.sempermechanics.semper.ui.common.transfer

import android.content.Context
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.ui.common.dialog.Feedback
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a screen's request for restores came to ([RestoreStart.request]).
 * Home and Settings ask the same way and say "Restore paused" the same way.
 */
sealed interface RestoreRequest {

    /** Restore is off for this account (licence, policy, kill switch); nothing was queued. */
    data object Paused : RestoreRequest

    /** [total] asked for; [counts] says how many were queued now and how many were already running. */
    data class Queued(val total: Int, val counts: RestoreStart.Counts) : RestoreRequest

    companion object {
        /**
         * Whether this account may restore now. When it may not, the user is
         * told so ("Restore paused") and nothing should be asked of them first.
         */
        fun allowedOrSay(context: Context): Boolean {
            val allowed = LicenseEntitlements.restoreEnabled(context)
            if (!allowed) sayPaused(context)
            return allowed
        }

        /** The one "Restore paused" toast. Main thread. */
        fun sayPaused(context: Context) {
            Feedback.toast(context, R.string.restore_paused, long = true)
        }
    }
}

/**
 * The one way a screen asks for restores: [RestoreRequest.Paused] when this
 * account may not restore, else [targets] started through [RestoreStart.startAll]
 * on [io] (it writes the index). Suspends; safe to call from the main thread.
 */
suspend fun RestoreStart.request(
    context: Context,
    targets: List<RestoreStart.Target>,
    io: CoroutineDispatcher = Dispatchers.IO,
): RestoreRequest {
    if (!LicenseEntitlements.restoreEnabled(context)) return RestoreRequest.Paused
    val counts = withContext(io) { startAll(context, targets) }
    return RestoreRequest.Queued(targets.size, counts)
}
