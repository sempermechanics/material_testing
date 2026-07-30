package com.indicvision.semper.ui.auth

import android.app.Activity
import com.indicvision.semper.data.AccessStatus
import com.indicvision.semper.ui.home.HomeActivity

/**
 * Maps [AuthRepository][com.indicvision.semper.data.AuthRepository] access-status
 * strings to the next Activity. Splash, Auth, and PendingApproval all call this
 * so the three screens cannot drift on which statuses mean "in".
 */
object AccessRouter {

    /** True when the user may enter the main app (online or offline-approved). */
    fun isHomeStatus(status: String): Boolean =
        status == AccessStatus.APPROVED || status == AccessStatus.OFFLINE_CACHE_APPROVED

    /**
     * After a successful sign-in: pending accounts wait; everything else goes Home
     * (including offline-approved).
     */
    fun afterSignIn(status: String): Class<out Activity> =
        if (status == AccessStatus.PENDING) {
            PendingApprovalActivity::class.java
        } else {
            HomeActivity::class.java
        }

    /**
     * After a status refresh (Splash / PendingApproval). Unknown strings go to Auth
     * so the user can re-authenticate cleanly.
     *
     * @param stayOnPending when true, a still-PENDING result returns null (caller stays)
     * @return null when the caller should stay put
     */
    fun afterRefresh(status: String, stayOnPending: Boolean = false): Class<out Activity>? = when (status) {
        AccessStatus.APPROVED, AccessStatus.OFFLINE_CACHE_APPROVED -> HomeActivity::class.java
        AccessStatus.PENDING -> if (stayOnPending) null else PendingApprovalActivity::class.java
        else -> AuthActivity::class.java
    }
}
