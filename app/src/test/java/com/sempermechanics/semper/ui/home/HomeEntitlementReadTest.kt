package com.sempermechanics.semper.ui.home

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Home reads the licence state it shows off the main thread (readiness TE1-006,
 * ADR-016). That read reaches EntitlementStore, whose first read in a process opens
 * the Keystore and verifies the token, and whose clock re-seal is a Keystore HMAC;
 * on the main thread it ran there, or waited there on the start-up warm-up's lock.
 * Until the read lands Home shows its neutral state: no cloud badges, no restore.
 */
@RunWith(RobolectricTestRunner::class)
class HomeEntitlementReadTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val ioThread = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        ioThread.shutdownNow()
    }

    /** Runs the main looper until [vm] has published a list. */
    private fun awaitList(vm: HomeViewModel): HomeViewModel.HomeList {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S)
        while (vm.list.value == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        return requireNotNull(vm.list.value) { "no list within $TIMEOUT_S s" }
    }

    @Test
    fun `the entitlement is read on the io dispatcher, never on the main thread`() {
        val vm = HomeViewModel(app)
        vm.io = ioThread.asCoroutineDispatcher()
        val readOn = mutableListOf<Thread>()
        vm.readEntitlements = {
            readOn += Thread.currentThread()
            HomeViewModel.HomeEntitlements(cloudState = true, restoreEnabled = true)
        }
        assertEquals("neutral before the read lands", HomeViewModel.HomeEntitlements.NONE, vm.entitlements)

        vm.refreshList()
        val list = awaitList(vm)

        assertEquals(1, readOn.size)
        assertFalse("read on the main thread", readOn.single() == Looper.getMainLooper().thread)
        assertEquals(HomeViewModel.HomeEntitlements(cloudState = true, restoreEnabled = true), list.entitlements)
        assertEquals(list.entitlements, vm.entitlements)
    }

    @Test
    fun `the backups on offer are checked against the licence off the main thread`() {
        val vm = HomeViewModel(app)
        vm.io = ioThread.asCoroutineDispatcher()
        val readOn = mutableListOf<Thread>()
        vm.readEntitlements = {
            readOn += Thread.currentThread()
            HomeViewModel.HomeEntitlements.NONE
        }
        var offered: Int? = null
        val scope = MainScope()
        scope.launch { offered = vm.offeredBackups().size }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S)
        while (offered == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        scope.cancel()
        assertNotNull(offered)
        assertEquals("a Demo account is offered nothing", 0, offered)
        assertFalse("read on the main thread", readOn.single() == Looper.getMainLooper().thread)
    }

    private companion object {
        const val TIMEOUT_S = 5L
        const val POLL_MS = 5L
    }
}
