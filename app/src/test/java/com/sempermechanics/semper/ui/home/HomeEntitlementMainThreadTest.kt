package com.sempermechanics.semper.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.entitlement.EntitlementStore
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.QuotaBackendOn
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.ui.common.transfer.request
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Nothing Home does calls EntitlementStore on the main thread (readiness TE1-006,
 * ADR-016): its first read in a process opens the Keystore and verifies the token,
 * a clock re-seal is a Keystore HMAC and a rollback a `commit()`, all inside
 * `EntitlementStore.read`. Every path is driven with a floating licensed account, so
 * each gate reaches the store, and [EntitlementStore.onCall] records any call made on
 * the main looper's thread.
 */
@RunWith(RobolectricTestRunner::class)
class HomeEntitlementMainThreadTest {

    @get:Rule
    val clean = CleanAppState()

    @get:Rule
    val backend = QuotaBackendOn()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val calls = AtomicInteger()
    private val onMain = CopyOnWriteArrayList<String>()
    private var home: ActivityController<HomeActivity>? = null

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // No first-run prompt over the screen.
        AccountCache.setBetaNoticeAcked(context)
        AppSettings.setDiagnosticsEnabled(context, false)
        AppRemoteConfig.apply(context, AppConfigDto(mode = "licensed", maxSessions = 500, licenseSeating = "floating"))
        EntitlementStore.onCall = {
            calls.incrementAndGet()
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onMain += Throwable().stackTrace.drop(1).take(FRAMES)
                    .joinToString(" < ") { "${it.className}.${it.methodName}" }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        EntitlementStore.onCall = null
        home?.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        AppRemoteConfig.clear(context)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Home, started and showing its first list (quota and licence lines rendered). */
    private fun startedHome(): HomeActivity {
        val built = Robolectric.buildActivity(HomeActivity::class.java).setup().also { home = it }
        val activity = built.get()
        val vm = ViewModelProvider(activity)[HomeViewModel::class.java]
        idleUntil("Home's first list") { vm.list.value != null }
        idle()
        return activity
    }

    private fun assertNoneOnMain(path: String) {
        assertTrue("$path never reached EntitlementStore, so proves nothing", calls.get() > 0)
        assertEquals("$path called EntitlementStore on the main thread", emptyList<String>(), onMain.toList())
    }

    @Test
    fun `Home's start, list, quota and licence lines read the entitlement off the main thread`() {
        startedHome()
        assertNoneOnMain("Home's start")
    }

    @Test
    fun `a tap on New analysis checks the seat and the limit off the main thread`() {
        val activity = startedHome()
        calls.set(0)
        onMain.clear()

        activity.findViewById<View>(R.id.fabNewAnalysis).performClick()
        // The seat check and the limit check each reach the store.
        idleUntil("the seat and limit checks") { calls.get() >= 2 }
        idle()
        assertNoneOnMain("New analysis")
    }

    @Test
    fun `a tap on the quota line checks the limit off the main thread`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).also {
            it.get().setTheme(R.style.Theme_Semper)
        }.setup().get()
        val quotaView = TextView(activity)
        var settings = 0
        val card = HomeQuotaCard(activity, quotaView, TextView(activity)) { settings++ }
        val nearTheCeiling = HomeQuotaCard.Lines(shownCap = 500, expiryNoticeDays = null, inGrace = false)
        card.render(localSessionCount = 499, lines = nearTheCeiling)
        calls.set(0)

        quotaView.performClick()
        idleUntil("Settings") { settings == 1 }
        assertNoneOnMain("The quota line")

        calls.set(0)
        runBlocking { card.openLimitScreenIfReached(localCount = 3) }
        assertNoneOnMain("The limit check")
    }

    @Test
    fun `a restore asks the licence off the main thread`() {
        runBlocking { RestoreStart.request(context, emptyList()) }
        assertNoneOnMain("A restore request")
    }

    private companion object {
        const val FRAMES = 8
    }
}
