package com.sempermechanics.semper.ui.limit

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.cloud.FakeCloudApi
import com.sempermechanics.semper.cloud.FakeTokens
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.fixtures.CleanAppState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * "Refresh licence" ([LicenseRefresh.run]): one `/v1/config` fetch whose
 * answer is stored, a retry queued when it does not answer, and whether the
 * licence the screens read changed.
 */
@RunWith(RobolectricTestRunner::class)
class LicenseRefreshTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()

    @Before
    fun startWorkManager() = WorkManagerTestInitHelper.initializeTestWorkManager(context)

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        AppRemoteConfig.clear(context)
    }

    private fun retryQueued(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("license-config-now").get()

    private val demo = AppConfigDto(mode = "demo", maxSessions = 25)

    @Test
    fun `a seat attached on the server is stored, said, and counts as a change`() = runBlocking {
        AppRemoteConfig.apply(context, demo)
        api.onGetConfig = {
            AppConfigDto(mode = "licensed", licenseKind = "institution", maxSessions = 500, cloudBackupEnabled = true)
        }

        val outcome = LicenseRefresh.run(context, api, tokens)

        assertEquals(LicenseRefreshResult.LICENSED_INSTITUTION, outcome.result)
        assertTrue(outcome.changed)
        assertTrue(LicenseEntitlements.isLicensed(context))
        assertEquals(listOf("getConfig"), api.calls)
        assertEquals(emptyList<WorkInfo>(), retryQueued())
    }

    @Test
    fun `an account still on the demo is told so, and nothing changed`() = runBlocking {
        AppRemoteConfig.apply(context, demo)
        api.onGetConfig = { demo }

        val outcome = LicenseRefresh.run(context, api, tokens)

        assertEquals(LicenseRefreshResult.DEMO, outcome.result)
        assertFalse(outcome.changed)
    }

    @Test
    fun `a licence the server withdrew is a change too`() = runBlocking {
        AppRemoteConfig.apply(context, AppConfigDto(mode = "licensed", licenseKind = "individual", maxSessions = 500))
        api.onGetConfig = { demo }

        val outcome = LicenseRefresh.run(context, api, tokens)

        assertEquals(LicenseRefreshResult.DEMO, outcome.result)
        assertTrue(outcome.changed)
    }

    @Test
    fun `a failed fetch keeps the cached licence and queues the retrying refresh`() = runBlocking {
        AppRemoteConfig.apply(context, demo)
        api.onGetConfig = { throw IOException("offline") }

        val outcome = LicenseRefresh.run(context, api, tokens)

        assertEquals(LicenseRefreshResult.UNREACHABLE, outcome.result)
        assertFalse(outcome.changed)
        assertEquals(WorkInfo.State.ENQUEUED, retryQueued().single().state)
    }

    @Test
    fun `no usable sign-in makes no call and queues the retry for when there is one`() = runBlocking {
        tokens.token = null

        val outcome = LicenseRefresh.run(context, api, tokens)

        assertEquals(LicenseRefreshResult.UNREACHABLE, outcome.result)
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(1, retryQueued().size)
    }
}
