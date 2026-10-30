package com.sempermechanics.semper.data.account

import android.content.Context
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.LicenseConfigWorker
import com.sempermechanics.semper.data.account.AuthRepository.AccessLostException
import com.sempermechanics.semper.data.account.entitlement.ConfigFetch
import com.sempermechanics.semper.data.account.entitlement.EntitlementRefresh
import com.sempermechanics.semper.data.account.entitlement.SignInRefresh
import com.sempermechanics.semper.data.cloud.sync.PhoneSyncWorker
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.DeviceConflictException
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.HttpFailure.Kind
import com.sempermechanics.semper.data.net.MeResponse
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.util.suspendRunCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import timber.log.Timber
import java.io.IOException

/**
 * Asks the backend whether the signed-in account may use the app, and caches
 * the answer: [AccessStatus], role, the Terms state, `/v1/config` and the
 * device binding.
 *
 * A refusal the server meant ([AccessLostException]) is kept apart from no
 * answer at all, which falls back to the cached status: a 5xx or no network
 * must not throw someone already in the app back to the sign-in screen.
 */
internal class AccessStatusResolver(
    context: Context,
    private val api: CloudApi,
    private val tokens: TokenSource,
) {

    private val appContext = context.applicationContext

    suspend fun resolve(): Result<String> {
        // No backend configured: there is no URL to ask, so treat it as offline
        // (OkHttp throws IllegalArgumentException on the bare "/v1/me" path).
        val token = (if (api.enabled) tokens.usableIdToken() else null) ?: return offlineOrExpired()
        return try {
            approve(token)
        } catch (e: IOException) {
            refused(HttpFailure.classify(e))
        }
    }

    /** `/v1/me` said yes (it throws otherwise): cache what it and `/v1/config` said, and bind the device. */
    private suspend fun approve(token: String): Result<String> {
        val (me, config) = meAndConfig(token)
        AccountCache.setStatus(appContext, AccessStatus.APPROVED)
        AccountCache.setRole(appContext, me.role ?: "user")
        cacheLegalState(me)
        syncPendingTermsAcceptance(token)
        // The periodic refresh starts once there is a config to keep fresh,
        // on sign-in and on every launch's status check alike. KEEP makes a
        // repeat free.
        val answered = AppRemoteConfig.record(appContext, config)
        if (answered) LicenseConfigWorker.enqueue(appContext)
        // The sync from other phones, while its switch is on; it checks the
        // licence on each run. KEEP makes a repeat free here too.
        PhoneSyncWorker.schedule(appContext)
        val registeredNow = ensureDeviceRegistered(token)
        // `/v1/config`, and the entitlement token only it carries, is stored
        // before this returns and Home opens (EntitlementRefresh.afterSignIn):
        // asked again after this device was first registered, otherwise left
        // to the retrying one-off when the read failed.
        val firstFetch = if (answered) ConfigFetch.ANSWERED else ConfigFetch.FAILED
        when (EntitlementRefresh.afterSignIn(firstFetch, registeredNow)) {
            SignInRefresh.NONE -> Unit
            SignInRefresh.QUEUE -> LicenseConfigWorker.refreshNow(appContext)
            SignInRefresh.FETCH_AGAIN ->
                if (AppRemoteConfig.record(appContext, suspendRunCatching { api.getConfig(token) })) {
                    LicenseConfigWorker.enqueue(appContext)
                } else {
                    LicenseConfigWorker.refreshNow(appContext)
                }
        }
        return Result.success(AccessStatus.APPROVED)
    }

    /**
     * `/v1/me` and `/v1/config` are independent reads: in parallel they cost
     * one round-trip instead of two. A failed /me cancels the config call; a
     * failed config call fails only the config.
     */
    private suspend fun meAndConfig(token: String): Pair<MeResponse, Result<AppConfigDto>> {
        val (me, fetched) = coroutineScope {
            val config = async { suspendRunCatching { api.getConfig(token) } }
            api.getMe(token) to config.await() // 200 = APPROVED
        }
        // A config read just before /v1/me's invite claim landed says demo,
        // and a known config is not refetched while reconciles are
        // throttled, so a new licensed user saw demo's 25. Ask once more.
        val config = if (fetched.getOrNull()?.let { me.license?.disagreesWith(it) } == true) {
            suspendRunCatching { api.getConfig(token) }
        } else {
            fetched
        }
        return me to config
    }

    /** What a failed status check means for this account. */
    private fun refused(failure: HttpFailure): Result<String> = when (failure.kind) {
        Kind.NOT_APPROVED -> {
            Timber.d(failure.cause, "Account is pending approval")
            AccountCache.setStatus(appContext, AccessStatus.PENDING)
            Result.success(AccessStatus.PENDING)
        }
        Kind.DEVICE_CONFLICT, Kind.DEVICE_IN_USE -> Result.failure(deviceBoundElsewhere(failure.cause))
        Kind.UNAUTHORIZED -> Result.failure(AccessLostException(unauthorizedMessage(failure.body)))
        Kind.FORBIDDEN, Kind.NOT_FOUND, Kind.CONFLICT, Kind.RATE_LIMITED, Kind.SERVER, Kind.REJECTED -> {
            Timber.w(failure.cause, "Status check refused (HTTP %s)", failure.code)
            val shown = appContext.getString(failure.userMessage(R.string.auth_account_check_failed))
            Result.failure(UserMessageException(shown))
        }
        // No answer about this account (the rest are IOExceptions that are not one).
        Kind.OFFLINE, Kind.DEVICE_NOT_ACTIVE, Kind.NO_SEAT, Kind.TERMS_MISMATCH, Kind.UNEXPECTED -> {
            Timber.d(failure.cause, "Status check failed offline; using cached status")
            offlineOrExpired()
        }
    }

    /**
     * The gateway or backend rejected the Firebase ID token (wrong audience,
     * expired, or malformed). The user is told the session expired; the
     * server's short hint, the clue to a misconfigured FIREBASE_PROJECT_ID /
     * API Gateway JWT audience, goes to the log, not the screen (AE1-009).
     */
    private fun unauthorizedMessage(body: String): String {
        val hint = ApiErrors.detailOf(body).lineSequence().firstOrNull().orEmpty()
            .take(API_ERROR_HINT_MAX_CHARS)
        if (hint.isNotBlank()) Timber.w("Sign-in rejected by the API (401): %s", hint)
        return appContext.getString(R.string.error_session_expired)
    }

    private fun deviceBoundElsewhere(cause: Throwable) = AccessLostException(
        appContext.getString(
            if ((cause as? DeviceConflictException)?.released == true) {
                R.string.auth_phone_released
            } else {
                R.string.auth_phone_linked_elsewhere
            },
        ),
        cause,
    )

    private fun offlineOrExpired(): Result<String> =
        if (AccountCache.cachedStatus(appContext) == AccessStatus.APPROVED) {
            Result.success(AccessStatus.OFFLINE_CACHE_APPROVED)
        } else {
            Result.failure(UserMessageException(appContext.getString(R.string.auth_account_check_offline)))
        }

    /**
     * The server's view of the Terms wins over this device's: a version bump
     * re-gates on the next launch, and an acceptance made on another device
     * (or before a reinstall) is honoured without asking again.
     */
    private fun cacheLegalState(me: MeResponse) {
        val terms = me.terms ?: return
        AccountCache.setTermsRequiredVersion(appContext, terms.requiredVersion)
        val accepted = terms.acceptedVersion
        if (accepted != null && AccountCache.termsAcceptedVersion(appContext) != accepted) {
            AccountCache.setTermsAccepted(appContext, accepted, synced = true)
        }
        me.improvementConsent?.let { AccountCache.setImprovementConsent(appContext, it) }
    }

    /** Push a locally recorded acceptance the backend has not confirmed yet. */
    private suspend fun syncPendingTermsAcceptance(token: String) {
        if (AccountCache.isTermsAcceptanceSynced(appContext)) return
        val version = AccountCache.termsAcceptedVersion(appContext) ?: return
        suspendRunCatching { api.acceptTerms(token, version) }
            .onSuccess { AccountCache.setTermsAccepted(appContext, version, synced = true) }
            .onFailure { Timber.d(it, "Terms acceptance still not synced") }
    }

    /** Registers this device once; true when it was registered just now. */
    private suspend fun ensureDeviceRegistered(idToken: String): Boolean {
        if (AccountCache.isDeviceRegistered(appContext)) return false
        api.registerDevice(idToken) // throws DeviceConflictException on 409
        AccountCache.setDeviceRegistered(appContext, true)
        Timber.d("Device registered with backend")
        return true
    }

    private companion object {
        /** Cap server error detail length in the 401 log line. */
        const val API_ERROR_HINT_MAX_CHARS = 120
    }
}
