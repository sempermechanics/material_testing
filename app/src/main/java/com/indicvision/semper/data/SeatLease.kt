package com.indicvision.semper.data

import android.content.Context
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import timber.log.Timber

/**
 * Floating-seat lease: release on sign-out, renew while the process is up.
 *
 * The backend treats a fresh [IndicApi.checkoutLease] as the heartbeat — there
 * is no separate heartbeat route. Config refresh (revoke / expiry) is a
 * separate [IndicApi.getConfig] call; see [LicenseConfigWorker].
 */
object SeatLease {

    /**
     * True when this account currently holds a floating seat that should be
     * returned to the pool on sign-out or renewed while the app is open.
     */
    fun holdsFloatingSeat(context: Context): Boolean =
        LicenseEntitlements.needsSeat(context) && LicenseEntitlements.isLicensed(context)

    /**
     * Best-effort release before tokens are cleared. Never throws; a quiet
     * failure leaves the seat until the lease TTL, which the product already
     * tolerates.
     */
    suspend fun releaseBestEffort(context: Context) {
        release(
            shouldRelease = { holdsFloatingSeat(context) },
            token = { TokenProvider.usableIdToken() },
            apiEnabled = { IndicApi.get(context).enabled },
            release = { token -> IndicApi.get(context).releaseLease(token) },
            applyConfig = { AppRemoteConfig.apply(context, it) },
        )
    }

    /**
     * Renew a held floating seat. Same best-effort contract as release — a
     * failure just means the next cycle (or a lapsed TTL) will demote.
     */
    suspend fun heartbeatBestEffort(context: Context) {
        heartbeat(
            shouldHeartbeat = { holdsFloatingSeat(context) },
            token = { TokenProvider.usableIdToken() },
            apiEnabled = { IndicApi.get(context).enabled },
            checkout = { token -> IndicApi.get(context).checkoutLease(token) },
            applyConfig = { AppRemoteConfig.apply(context, it) },
        )
    }

    /**
     * Fetch `/v1/config`, apply it, and re-checkout when the account still
     * looks licensed on a floating seat. Used by the background worker so an
     * idle phone learns a remote revoke without an open screen.
     */
    suspend fun refreshConfigAndSeatBestEffort(context: Context) {
        val api = IndicApi.get(context)
        if (!api.enabled) return
        val token = TokenProvider.usableIdToken() ?: return
        runCatching { api.getConfig(token) }
            .onSuccess { AppRemoteConfig.apply(context, it) }
            .onFailure {
                AppRemoteConfig.recordFetchFailure(context)
                Timber.d(it, "Background license config refresh failed")
                return
            }
        if (holdsFloatingSeat(context)) {
            heartbeatBestEffort(context)
        }
    }

    /** Injectable half of [releaseBestEffort] — order and gates are what tests pin. */
    internal suspend fun release(
        shouldRelease: () -> Boolean,
        token: suspend () -> String?,
        apiEnabled: () -> Boolean,
        release: suspend (String) -> AppConfigDto,
        applyConfig: (AppConfigDto) -> Unit,
    ): Boolean {
        if (!shouldRelease() || !apiEnabled()) return false
        val idToken = token() ?: return false
        return runCatching {
            applyConfig(release(idToken))
            true
        }.onFailure { Timber.w(it, "Could not release floating seat on sign-out") }
            .getOrDefault(false)
    }

    /** Injectable half of [heartbeatBestEffort]. */
    internal suspend fun heartbeat(
        shouldHeartbeat: () -> Boolean,
        token: suspend () -> String?,
        apiEnabled: () -> Boolean,
        checkout: suspend (String) -> AppConfigDto,
        applyConfig: (AppConfigDto) -> Unit,
    ): Boolean {
        if (!shouldHeartbeat() || !apiEnabled()) return false
        val idToken = token() ?: return false
        return runCatching {
            applyConfig(checkout(idToken))
            true
        }.onFailure { Timber.w(it, "Floating-seat heartbeat failed") }
            .getOrDefault(false)
    }
}
