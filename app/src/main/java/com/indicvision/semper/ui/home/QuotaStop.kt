package com.indicvision.semper.ui.home

import android.content.Context
import com.indicvision.semper.data.net.TokenStore

/** Home's half of the analysis-limit bookkeeping. */
internal object QuotaStop {

    /**
     * Folds the phone's analysis count into the limit, without lifting a stop
     * already in force.
     *
     * The upload worker forces the stop when the backend refuses a backup for
     * the account's limit (`TokenStore.setSessionLimitReached`), with no fresh
     * numbers to go on. [TokenStore.refreshSessionLimit] clears that flag, and
     * Home called it on every start and refresh, so the stop was gone before
     * the user next tapped **+**. Only fresh server numbers (a reconcile's
     * `setQuota`) or a delete (through `SessionStore`) lift it now.
     */
    fun foldLocalCount(context: Context, localCount: Int) {
        val stopped = TokenStore.isSessionLimitReached(context)
        TokenStore.refreshSessionLimit(context, localCount)
        if (stopped && !TokenStore.isSessionLimitReached(context)) {
            TokenStore.setSessionLimitReached(context, true)
        }
    }
}
