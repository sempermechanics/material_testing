package com.sempermechanics.semper.auth

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.cloud.FakeCloudApi
import com.sempermechanics.semper.cloud.FakeTokens
import com.sempermechanics.semper.data.account.AccessStatus
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.DeviceConflictException
import com.sempermechanics.semper.data.net.DeviceInUseException
import com.sempermechanics.semper.data.net.DeviceNotActiveException
import com.sempermechanics.semper.data.net.MeLicenseDto
import com.sempermechanics.semper.data.net.MeResponse
import com.sempermechanics.semper.data.net.NoSeatAvailableException
import com.sempermechanics.semper.data.net.NotApprovedException
import com.sempermechanics.semper.data.net.TermsDto
import com.sempermechanics.semper.data.net.TermsVersionMismatchException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * What [AuthRepository] concludes from the backend's answers (ADR-002). The
 * distinction under test is the one that decides the first screen: a refusal
 * the server meant sends the user back to sign-in, while a server fault or no
 * network must not throw out someone who was already approved.
 */
@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()
    private var signedIn = true

    private val repo = AuthRepository(context, api, tokens, signedIn = { signedIn })

    @Before
    fun startWorkManager() = WorkManagerTestInitHelper.initializeTestWorkManager(context)

    // Robolectric keeps statics between test classes, and other tests rely on
    // WorkManager not being started; the helper set it through setDelegate.
    @SuppressLint("RestrictedApi")
    @After
    fun stopWorkManager() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun licenceRefresh(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("license-config-refresh").get()

    /** The retrying one-off `/v1/config` refresh (`LicenseConfigWorker.refreshNow`). */
    private fun retryQueued(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("license-config-now").get()

    private fun approved(terms: TermsDto? = null, consent: Boolean? = null) =
        MeResponse(uid = "u1", role = "admin", accessStatus = "APPROVED", terms = terms, improvementConsent = consent)

    private fun serverUp(me: MeResponse = approved()) {
        api.onMe = { me }
        api.onGetConfig = { throw IOException("config down") }
        api.onRegisterDevice = {}
    }

    // ------------------------------------------------------------ status

    @Test
    fun `no Firebase user means no backend call`() = runBlocking {
        signedIn = false

        assertTrue(repo.refreshStatus().isFailure)
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `an approved account registers this device once`() = runBlocking {
        serverUp()

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())
        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())

        assertEquals("admin", AccountCache.cachedRole(context))
        assertTrue(AccountCache.isDeviceRegistered(context))
        assertEquals(1, api.calls.count { it == "registerDevice" })
    }

    @Test
    fun `a failed config read does not fail the sign-in`() = runBlocking {
        serverUp()

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())
        assertTrue("config still fetched", "getConfig" in api.calls)
    }

    @Test
    fun `a status check that records a config schedules the licence refresh`() = runBlocking {
        serverUp()
        api.onGetConfig = { AppConfigDto(maxSessions = 25, mode = "demo") }

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())

        assertEquals(WorkInfo.State.ENQUEUED, licenceRefresh().single().state)
    }

    @Test
    fun `an approved status check schedules the sync from other phones`() = runBlocking {
        serverUp()

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())

        val sync = WorkManager.getInstance(context).getWorkInfosForUniqueWork("phone-sync-periodic").get()
        assertEquals(WorkInfo.State.ENQUEUED, sync.single().state)
    }

    @Test
    fun `a status check whose config read failed schedules no refresh`() = runBlocking {
        serverUp()

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())

        assertEquals(emptyList<WorkInfo>(), licenceRefresh())
    }

    @Test
    fun `a config read before the invite claim landed is asked again`() = runBlocking {
        AccountCache.setDeviceRegistered(context, true)
        serverUp(approved().copy(license = MeLicenseDto(mode = "licensed")))
        val answers = ArrayDeque(
            listOf(
                AppConfigDto(maxSessions = 25, mode = "demo"),
                AppConfigDto(maxSessions = 999, mode = "licensed"),
            ),
        )
        api.onGetConfig = { answers.removeFirst() }

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())
        assertEquals(2, api.calls.count { it == "getConfig" })
        assertEquals(999, AppRemoteConfig.maxSessions(context))
    }

    @Test
    fun `a config that agrees with me is fetched once`() = runBlocking {
        AccountCache.setDeviceRegistered(context, true)
        serverUp(approved().copy(license = MeLicenseDto(mode = "demo")))
        api.onGetConfig = { AppConfigDto(maxSessions = 25, mode = "demo") }

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())
        assertEquals(1, api.calls.count { it == "getConfig" })
        assertEquals(emptyList<WorkInfo>(), retryQueued())
    }

    // ------------------------------------------------- config on sign-in

    @Test
    fun `a first sign-in on this phone stores the config read after the device is registered`() = runBlocking {
        // The backend issues the entitlement token only to a registered
        // device, so the read beside /v1/me carries none on a new phone.
        serverUp()
        val answers = ArrayDeque(
            listOf(
                AppConfigDto(maxSessions = 25, mode = "demo"),
                AppConfigDto(maxSessions = 500, mode = "licensed", cloudBackupEnabled = true),
            ),
        )
        api.onGetConfig = { answers.removeFirst() }

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())

        val order = api.calls.filter { it == "getConfig" || it == "registerDevice" }
        assertEquals(listOf("getConfig", "registerDevice", "getConfig"), order)
        assertEquals("the second answer is the one stored", 500, AppRemoteConfig.maxSessions(context))
        assertEquals(WorkInfo.State.ENQUEUED, licenceRefresh().single().state)
        assertEquals(emptyList<WorkInfo>(), retryQueued())
    }

    @Test
    fun `a first sign-in whose second config read fails queues the retrying refresh`() = runBlocking {
        serverUp()
        val answers = ArrayDeque<() -> AppConfigDto>(
            listOf({ AppConfigDto(maxSessions = 25, mode = "demo") }, { throw IOException("dropped") }),
        )
        api.onGetConfig = { answers.removeFirst()() }

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())

        assertEquals(2, api.calls.count { it == "getConfig" })
        assertEquals(25, AppRemoteConfig.maxSessions(context))
        assertEquals(WorkInfo.State.ENQUEUED, retryQueued().single().state)
    }

    @Test
    fun `a later status check on a registered phone asks config once`() = runBlocking {
        AccountCache.setDeviceRegistered(context, true)
        serverUp()
        api.onGetConfig = { AppConfigDto(maxSessions = 25, mode = "demo") }

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())

        assertEquals(1, api.calls.count { it == "getConfig" })
        assertFalse("registerDevice" in api.calls)
        assertEquals(emptyList<WorkInfo>(), retryQueued())
    }

    @Test
    fun `a failed config read on a registered phone queues the retrying refresh`() = runBlocking {
        AccountCache.setDeviceRegistered(context, true)
        serverUp()

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())

        assertEquals(1, api.calls.count { it == "getConfig" })
        assertEquals(WorkInfo.State.ENQUEUED, retryQueued().single().state)
    }

    @Test
    fun `an unapproved account waits on the pending screen`() = runBlocking {
        api.onMe = { throw NotApprovedException() }
        api.onGetConfig = { throw IOException("config down") }

        assertEquals(AccessStatus.PENDING, repo.refreshStatus().getOrThrow())
        assertEquals(AccessStatus.PENDING, AccountCache.cachedStatus(context))
    }

    @Test
    fun `a device bound elsewhere loses access`() = runBlocking {
        api.onMe = { throw DeviceInUseException() }
        api.onGetConfig = { throw IOException("config down") }

        val error = repo.refreshStatus().exceptionOrNull()
        assertTrue("got $error", error is AuthRepository.AccessLostException)
    }

    @Test
    fun `a 401 loses access and says the session expired, not the server's hint`() = runBlocking {
        api.onMe = { throw ApiException(401, """{"detail":"bad audience"}""") }
        api.onGetConfig = { throw IOException("config down") }

        val error = repo.refreshStatus().exceptionOrNull()
        assertTrue("got $error", error is AuthRepository.AccessLostException)
        // The hint is for the log; the user reads the app's words (readiness AE1-009).
        assertEquals(context.getString(R.string.error_session_expired), error!!.message)
    }

    @Test
    fun `a server fault is an error, not a lost session`() = runBlocking {
        api.onMe = { throw ApiException(500, "") }
        api.onGetConfig = { throw IOException("config down") }

        val error = repo.refreshStatus().exceptionOrNull()
        assertTrue("got $error", error != null && error !is AuthRepository.AccessLostException)
    }

    @Test
    fun `other refusals from the server are an error in the app's words, without their status`() = runBlocking {
        val expected = mapOf(
            403 to R.string.auth_account_check_failed,
            404 to R.string.auth_account_check_failed,
            429 to R.string.error_cloud_unavailable,
            500 to R.string.error_cloud_unavailable,
        )
        for ((code, message) in expected) {
            api.onMe = { throw ApiException(code, "") }
            api.onGetConfig = { throw IOException("config down") }

            val error = repo.refreshStatus().exceptionOrNull()

            assertTrue("$code: got $error", error != null && error !is AuthRepository.AccessLostException)
            assertEquals("$code", context.getString(message), error!!.message)
        }
    }

    @Test
    fun `a device bound to another account loses access`() = runBlocking {
        api.onMe = { throw DeviceConflictException() }
        api.onGetConfig = { throw IOException("config down") }

        assertTrue(repo.refreshStatus().exceptionOrNull() is AuthRepository.AccessLostException)
    }

    @Test
    fun `a phone staff released is told so, not that it is linked elsewhere`() = runBlocking {
        serverUp()
        api.onRegisterDevice = { throw DeviceConflictException(released = true) }

        val lost = repo.refreshStatus().exceptionOrNull()

        assertTrue("got $lost", lost is AuthRepository.AccessLostException)
        assertTrue(lost!!.message!!.startsWith("Staff released this phone"))
    }

    @Test
    fun `a registration conflict keeps the linked-elsewhere message`() = runBlocking {
        serverUp()
        api.onRegisterDevice = { throw DeviceConflictException() }

        val lost = repo.refreshStatus().exceptionOrNull()

        assertTrue(lost!!.message!!, lost.message!!.startsWith("This phone is linked to another account"))
        // The handset is a "phone" in user copy (readiness AE3-002).
        assertFalse(lost.message!!, Regex("""\bdevice\b""").containsMatchIn(lost.message!!))
    }

    @Test
    fun `failures that say nothing about the account fall back to the cached status`() = runBlocking {
        val notAnAnswer = listOf<() -> Nothing>(
            { throw DeviceNotActiveException() },
            { throw NoSeatAvailableException() },
            { throw TermsVersionMismatchException() },
        )
        for (failure in notAnAnswer) {
            api.onMe = { failure() }
            api.onGetConfig = { throw IOException("config down") }
            AccountCache.setStatus(context, AccessStatus.PENDING)
            val unknown = repo.refreshStatus().exceptionOrNull()
            assertTrue("got $unknown", unknown != null && unknown !is AuthRepository.AccessLostException)

            AccountCache.setStatus(context, AccessStatus.APPROVED)
            assertEquals(AccessStatus.OFFLINE_CACHE_APPROVED, repo.refreshStatus().getOrThrow())
        }
    }

    @Test
    fun `offline keeps an approved account in, and nobody else`() = runBlocking {
        api.onMe = { throw IOException("no route") }
        api.onGetConfig = { throw IOException("no route") }

        assertTrue(repo.refreshStatus().isFailure)

        AccountCache.setStatus(context, AccessStatus.APPROVED)
        assertEquals(AccessStatus.OFFLINE_CACHE_APPROVED, repo.refreshStatus().getOrThrow())
    }

    @Test
    fun `no token is treated like offline`() = runBlocking {
        tokens.token = null
        AccountCache.setStatus(context, AccessStatus.APPROVED)

        assertEquals(AccessStatus.OFFLINE_CACHE_APPROVED, repo.refreshStatus().getOrThrow())
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `no backend configured is treated like offline`() = runBlocking {
        // A build without SEMPER_API_BASE_URL that still holds a signed-in,
        // approved session opens Home from cache; the background re-check
        // must not call /v1/me with an empty base URL (the process died).
        api.enabled = false

        assertTrue(repo.refreshStatus().isFailure)

        AccountCache.setStatus(context, AccessStatus.APPROVED)
        assertEquals(AccessStatus.OFFLINE_CACHE_APPROVED, repo.refreshStatus().getOrThrow())
        assertEquals(emptyList<String>(), api.calls)
    }

    // ------------------------------------------------------------- terms

    @Test
    fun `terms accepted offline open the gate and sync on the next status check`() = runBlocking {
        api.enabled = false
        assertTrue(repo.acceptTerms("2026-09", improvementConsent = false).isSuccess)
        assertEquals("2026-09", AccountCache.termsAcceptedVersion(context))
        assertFalse(AccountCache.isTermsAcceptanceSynced(context))
        assertEquals(emptyList<String>(), api.calls)

        api.enabled = true
        serverUp()
        val sent = mutableListOf<String>()
        api.onAcceptTerms = { _, version -> sent += version }
        repo.refreshStatus().getOrThrow()

        assertEquals(listOf("2026-09"), sent)
        assertTrue(AccountCache.isTermsAcceptanceSynced(context))
    }

    @Test
    fun `terms the server no longer serves do not open the gate`() = runBlocking {
        api.onAcceptTerms = { _, _ -> throw TermsVersionMismatchException() }

        val result = repo.acceptTerms("2025-01", improvementConsent = true)

        assertTrue(result.exceptionOrNull() is TermsVersionMismatchException)
        assertNull(AccountCache.termsAcceptedVersion(context))
        assertFalse("consent is not sent for refused terms", "setImprovementConsent" in api.calls)
    }

    @Test
    fun `an unreachable server keeps the acceptance for later`() = runBlocking {
        api.onAcceptTerms = { _, _ -> throw IOException("no route") }
        api.onSetImprovementConsent = { _, _ -> throw IOException("no route") }

        assertTrue(repo.acceptTerms("2026-09", improvementConsent = true).isSuccess)

        assertEquals("2026-09", AccountCache.termsAcceptedVersion(context))
        assertFalse(AccountCache.isTermsAcceptanceSynced(context))
        assertEquals(true, AccountCache.improvementConsent(context))
    }

    @Test
    fun `the server's record of the terms wins over this device's`() = runBlocking {
        serverUp(approved(terms = TermsDto(requiredVersion = "2026-09", acceptedVersion = "2026-09"), consent = true))

        repo.refreshStatus().getOrThrow()

        assertEquals("2026-09", AccountCache.termsAcceptedVersion(context))
        assertTrue(AccountCache.isTermsAcceptanceSynced(context))
        assertEquals(true, AccountCache.improvementConsent(context))
    }

    @Test
    fun `a consent change the server did not take is reported and not stored`() = runBlocking {
        AccountCache.setImprovementConsent(context, true)
        api.onSetImprovementConsent = { _, _ -> throw IOException("no route") }

        assertTrue(repo.setImprovementConsent(false).isFailure)
        // TD-306: the phone keeps the server's last accepted answer, not the refused one.
        assertEquals(true, AccountCache.improvementConsent(context))
    }

    @Test
    fun `a consent change the server took is stored`() = runBlocking {
        AccountCache.setImprovementConsent(context, true)
        val sent = mutableListOf<Boolean>()
        api.onSetImprovementConsent = { _, granted -> sent += granted }

        assertTrue(repo.setImprovementConsent(false).isSuccess)

        assertEquals(listOf(false), sent)
        assertEquals(false, AccountCache.improvementConsent(context))
    }

    @Test
    fun `a consent change with no token stays on this phone`() = runBlocking {
        tokens.token = null

        assertTrue(repo.setImprovementConsent(true).isSuccess)

        assertFalse("setImprovementConsent" in api.calls)
        assertEquals(true, AccountCache.improvementConsent(context))
    }
}
