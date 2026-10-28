package com.sempermechanics.semper.ui.home

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog

/**
 * A configuration change (rotation, font scale, dark theme, a resize) rebuilt
 * Home and closed the + sheet, the delete question and selection mode with
 * it, so a half-made choice was lost (readiness AE1-111). They come back on
 * the rebuilt screen.
 */
@RunWith(RobolectricTestRunner::class)
class HomeConfigChangeTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screens = mutableListOf<ActivityController<HomeActivity>>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // No first-run prompt over the screen.
        AccountCache.setBetaNoticeAcked(context)
        AppSettings.setDiagnosticsEnabled(context, false)
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        screens.forEach { it.close() }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun home(): ActivityController<HomeActivity> =
        Robolectric.buildActivity(HomeActivity::class.java).setup().also { built ->
            screens += built
            val vm = ViewModelProvider(built.get())[HomeViewModel::class.java]
            idleUntil("Home's first list") { vm.list.value != null }
        }

    private fun Dialog.activity(): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** The dialogs showing over [activity] whose content holds [viewId]. */
    private fun showingOn(activity: Activity, viewId: Int): List<Dialog> = ShadowDialog.getShownDialogs()
        .filter { it.isShowing && it.activity() === activity && it.findViewById<View>(viewId) != null }

    @Test
    fun `the + sheet is open again after a rotation`() {
        val first = home()
        val old = first.get()
        old.findViewById<View>(R.id.fabNewAnalysis).performClick()
        idleUntil("the + sheet") { showingOn(old, R.id.tvMediaTitle).isNotEmpty() }

        val rebuilt = first.recreate().get()

        idleUntil("the + sheet on the rebuilt Home") { showingOn(rebuilt, R.id.tvMediaTitle).isNotEmpty() }
        assertEquals("one sheet, not two", 1, showingOn(rebuilt, R.id.tvMediaTitle).size)
        assertTrue("the old screen's sheet is closed", showingOn(old, R.id.tvMediaTitle).isEmpty())
    }

    @Test
    fun `selection mode and the delete question survive a rotation`() {
        SessionStore.save(context, sessionRecord(id = "s1", name = "Beam test"), allowOverLimit = true)
        SessionStore.save(context, sessionRecord(id = "s2", name = "Coupon"), allowOverLimit = true)
        val first = home()
        val old = first.get()
        val list = old.findViewById<RecyclerView>(R.id.sessionList)
        // The list leads with a day header; the first long-pressable row is an analysis.
        val row = { (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isLongClickable } }
        idleUntil("an analysis row") { row() != null }
        assertTrue(row()!!.performLongClick())
        old.findViewById<View>(R.id.btnSelectionDelete).performClick()
        idleUntil("the delete question") { showingOn(old, android.R.id.message).isNotEmpty() }

        val rebuilt = first.recreate().get()

        idleUntil("the selection bar") { rebuilt.findViewById<View>(R.id.homeSelectionBar).visibility == View.VISIBLE }
        assertEquals(
            rebuilt.resources.getQuantityString(R.plurals.selection_count_fmt, 1, 1),
            rebuilt.findViewById<TextView>(R.id.tvSelectionCount).text.toString(),
        )
        idleUntil("the delete question on the rebuilt Home") {
            showingOn(rebuilt, android.R.id.message).isNotEmpty()
        }
        val message = showingOn(rebuilt, android.R.id.message).single()
            .findViewById<TextView>(android.R.id.message).text.toString()
        assertEquals(rebuilt.getString(R.string.delete_body_cant_undo), message)
        assertTrue("the old screen's question is closed", showingOn(old, android.R.id.message).isEmpty())
    }
}
