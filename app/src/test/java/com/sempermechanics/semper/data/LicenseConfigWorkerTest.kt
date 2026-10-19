package com.sempermechanics.semper.data

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
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * The licence refresh's WorkManager identity. WorkManager stores the unique
 * name and tag, so both are pinned here byte for byte: a renamed one would
 * queue a second refresh beside the one every installed phone already has.
 */
@RunWith(RobolectricTestRunner::class)
class LicenseConfigWorkerTest {

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

    private fun queued(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork("license-config-refresh").get()

    @Test
    fun `the refresh is queued once, every four hours on any network, under its stored name and tag`() {
        LicenseConfigWorker.enqueue(context)
        LicenseConfigWorker.enqueue(context)

        val info = queued().single()
        assertTrue(info.tags.contains("license-config"))
        assertTrue(info.tags.contains(LicenseConfigWorker::class.java.name))
        assertEquals(TimeUnit.HOURS.toMillis(4), info.periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test
    fun `cancel stops the refresh`() {
        LicenseConfigWorker.enqueue(context)

        LicenseConfigWorker.cancel(context)

        assertEquals(WorkInfo.State.CANCELLED, queued().single().state)
    }

    private fun queuedNow(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork("license-config-now").get()

    @Test
    fun `the one-off refresh is queued once, on any network, beside the periodic one`() {
        LicenseConfigWorker.enqueue(context)
        LicenseConfigWorker.refreshNow(context)
        LicenseConfigWorker.refreshNow(context)

        val info = queuedNow().single()
        assertTrue(info.tags.contains("license-config"))
        assertNull("one-off, not periodic", info.periodicityInfo)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals("the periodic refresh is untouched", 1, queued().size)
    }

    @Test
    fun `cancel stops the one-off refresh too`() {
        LicenseConfigWorker.refreshNow(context)

        LicenseConfigWorker.cancel(context)

        assertEquals(WorkInfo.State.CANCELLED, queuedNow().single().state)
    }

    @Test
    fun `with no token in play nothing is due, so nothing is queued`() {
        // A test build pins no entitlement keys, so the token is never used.
        LicenseConfigWorker.refreshNowIfDue(context)

        assertTrue(queuedNow().isEmpty())
    }

    @Test
    fun `the one-off finishes when there is no backend to ask, rather than retrying`() {
        val worker = TestListenableWorkerBuilder<LicenseConfigWorker>(context)
            .setInputData(workDataOf(LicenseConfigWorker.KEY_REFRESH_NOW to true))
            .build()

        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
    }
}
