package com.sempermechanics.semper.data.cloud.sync

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.cloud.FakeCloudApi
import com.sempermechanics.semper.cloud.FakeTokens
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.ensureTestFirebaseApp
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
import java.util.concurrent.TimeUnit

/**
 * The sync's WorkManager identity and its switch. The unique name and tag are
 * stored by WorkManager, so they are pinned here byte for byte.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneSyncWorkerTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

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

    private fun queued(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork("phone-sync-periodic").get()

    @Test
    fun `the switch is on by default`() {
        assertTrue(AppSettings.syncFromOtherPhonesEnabled(context))
    }

    @Test
    fun `the sync is queued once, every six hours on unmetered networks with the battery not low`() {
        PhoneSyncWorker.schedule(context)
        PhoneSyncWorker.schedule(context)

        val info = queued().single()
        assertTrue(info.tags.contains("phone-sync"))
        assertEquals(TimeUnit.HOURS.toMillis(6), info.periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresBatteryNotLow())
    }

    @Test
    fun `with the switch off nothing is queued`() {
        AppSettings.setSyncFromOtherPhonesEnabled(context, false)

        PhoneSyncWorker.schedule(context)

        assertTrue(queued().isEmpty())
    }

    @Test
    fun `switching off stops future runs and switching on queues it again`() {
        PhoneSyncWorker.schedule(context)

        PhoneSyncWorker.setEnabled(context, false)

        assertFalse(AppSettings.syncFromOtherPhonesEnabled(context))
        assertEquals(WorkInfo.State.CANCELLED, queued().single().state)

        PhoneSyncWorker.setEnabled(context, true)

        assertTrue(AppSettings.syncFromOtherPhonesEnabled(context))
        assertTrue(queued().any { !it.state.isFinished })
    }

    @Test
    fun `cancel stops the sync`() {
        PhoneSyncWorker.schedule(context)

        PhoneSyncWorker.cancel(context)

        assertEquals(WorkInfo.State.CANCELLED, queued().single().state)
    }

    @Test
    fun `sign-out stops the sync`() = runBlocking {
        ensureTestFirebaseApp(context)
        PhoneSyncWorker.schedule(context)

        AuthRepository(context, FakeCloudApi(), FakeTokens()).signOut(releaseSeat = false)

        assertEquals(WorkInfo.State.CANCELLED, queued().single().state)
    }

    @Test
    fun `a run that has nothing to do still succeeds, so the period carries on`() {
        // A demo account: the run stops before asking the backend anything.
        val worker = TestListenableWorkerBuilder<PhoneSyncWorker>(context).build()

        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
    }
}
