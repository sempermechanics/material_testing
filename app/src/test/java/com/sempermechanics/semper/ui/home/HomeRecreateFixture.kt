package com.sempermechanics.semper.ui.home

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Home built for a configuration change (readiness AE1-111): a signed-out
 * Home with no first-run prompt over it, and the dialogs and rows a test
 * looks for on the screen before and after `recreate()`.
 */
abstract class HomeRecreateFixture {

    @get:Rule
    val clean = CleanAppState()

    protected val context: Context = ApplicationProvider.getApplicationContext()
    private val screens = mutableListOf<ActivityController<HomeActivity>>()

    @Before
    fun setUpHome() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // No first-run prompt over the screen.
        AccountCache.setBetaNoticeAcked(context)
        AppSettings.setDiagnosticsEnabled(context, false)
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDownHome() {
        screens.forEach { it.close() }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    protected fun home(): ActivityController<HomeActivity> =
        Robolectric.buildActivity(HomeActivity::class.java).setup().also { built ->
            screens += built
            idleUntil("Home's first list") { viewModel(built.get()).list.value != null }
        }

    protected fun viewModel(activity: HomeActivity): HomeViewModel =
        ViewModelProvider(activity)[HomeViewModel::class.java]

    /**
     * An account with cloud backup and restore, as Home's list read reports
     * it; kept by the view model, so the rebuilt Home sees it too.
     */
    protected fun withCloud(screen: HomeActivity) {
        val vm = viewModel(screen)
        vm.readEntitlements = { HomeViewModel.HomeEntitlements(cloudState = true, restoreEnabled = true) }
        vm.refreshList()
        idleUntil("the cloud licence on Home") { vm.list.value?.entitlements?.restoreEnabled == true }
    }

    /** What of an analysis is on this phone. */
    protected enum class Data {
        /** Nothing: its frames were freed up, or it is only in the cloud. */
        NONE,

        /** Its results (a `.dat`), not its photos. */
        RESULTS,

        /** Its results and its photo in `raw_deformed/`: a Re-run needs only its reference picked. */
        PHOTOS,
    }

    /** A saved analysis named [name], with [data] on this phone. */
    protected fun saved(
        id: String,
        name: String,
        data: Data = Data.NONE,
        cloudId: String = "",
        syncState: SessionRecord.SyncState = SessionRecord.SyncState.LOCAL_ONLY,
    ): SessionRecord {
        val dir = SessionStore.dirFor(context, id)
        val local = data != Data.NONE
        if (local) File(dir, "frame_001.dat").writeText("1")
        if (data == Data.PHOTOS) {
            File(SessionStore.rawDeformedDir(dir).apply { mkdirs() }, "def_001.png").writeText("png")
        }
        val record = sessionRecord(
            id = id,
            name = name,
            sessionDir = if (local) dir.path else "",
            defNames = listOf("def_001.png"),
            cloudSessionId = cloudId,
            syncState = syncState,
        )
        SessionStore.save(context, record, allowOverLimit = true)
        return record
    }

    private fun Dialog.activity(): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** The dialogs showing over [activity]. */
    protected fun dialogsOn(activity: Activity): List<Dialog> = ShadowDialog.getShownDialogs()
        .filter { it.isShowing && it.activity() === activity }

    /** The dialogs showing over [activity] whose content holds [viewId]. */
    protected fun showingOn(activity: Activity, viewId: Int): List<Dialog> =
        dialogsOn(activity).filter { it.findViewById<View>(viewId) != null }

    /** The alerts showing over [activity] titled [title]. */
    protected fun titledOn(activity: Activity, title: CharSequence): List<AlertDialog> = dialogsOn(activity)
        .filterIsInstance<AlertDialog>()
        .filter { it.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString() == title.toString() }

    /** The one alert titled [title] over [activity], once it shows. */
    protected fun awaitTitled(activity: Activity, title: CharSequence, what: String): AlertDialog {
        idleUntil(what) { titledOn(activity, title).isNotEmpty() }
        return titledOn(activity, title).single()
    }

    protected fun AlertDialog.title(): String =
        findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString()

    protected fun AlertDialog.body(): String = findViewById<TextView>(android.R.id.message)?.text.toString()

    /** Home's analysis rows on screen (the list also holds day headers, which do not long-press). */
    protected fun rows(screen: Activity): List<View> {
        val list = screen.findViewById<RecyclerView>(com.sempermechanics.semper.R.id.sessionList)
        return (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isLongClickable }
    }

    /** The row whose title reads [name], once the list shows it. */
    protected fun row(screen: Activity, name: String): View {
        val find = {
            rows(screen).firstOrNull { row ->
                row.findViewById<TextView>(com.sempermechanics.semper.R.id.sessionTitle)?.text.toString() == name
            }
        }
        idleUntil("the row $name") { find() != null }
        return find()!!
    }
}
