package com.sempermechanics.semper.ui.home

import android.view.View
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.CloudBackupListing
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/**
 * Every other question and sheet Home can show comes back, with what it was
 * about, when a configuration change rebuilds Home (readiness AE1-111): a
 * plain dialog closed with the old screen and the rebuilt one asked nothing.
 * Each one's action still works from the rebuilt screen.
 */
@RunWith(RobolectricTestRunner::class)
class HomeQuestionsConfigChangeTest : HomeRecreateFixture() {

    @Test
    fun `Exit semper is asked again after a rotation, and Exit still leaves`() {
        val first = home()
        val old = first.get()
        old.onBackPressedDispatcher.onBackPressed()
        awaitTitled(old, old.getText(R.string.exit_semper_title), "the exit question")

        val rebuilt = first.recreate().get()

        val exit = awaitTitled(rebuilt, rebuilt.getText(R.string.exit_semper_title), "the exit question again")
        assertTrue("the old screen's question is closed", titledOn(old, exit.title()).isEmpty())
        exit.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idleUntil("Exit to close Home") { rebuilt.isFinishing }
    }

    @Test
    fun `Not on this phone is said again after a rotation`() {
        saved("gone", "Freed coupon")
        val first = home()
        val old = first.get()
        row(old, "Freed coupon").performClick()
        awaitTitled(old, old.getText(R.string.session_data_gone_title), "Not on this phone")

        val rebuilt = first.recreate().get()

        val notice = awaitTitled(rebuilt, rebuilt.getText(R.string.session_data_gone_title), "Not on this phone again")
        assertEquals(rebuilt.getString(R.string.session_data_gone_body), notice.body())
        assertTrue(titledOn(old, old.getText(R.string.session_data_gone_title)).isEmpty())
    }

    @Test
    fun `Can't re-run, from the selection bar, is said again with its reason and the selection`() {
        saved("gone", "Freed coupon")
        val first = home()
        val old = first.get()
        assertTrue(row(old, "Freed coupon").performLongClick())
        old.findViewById<View>(R.id.btnSelectionRerun).performClick()
        awaitTitled(old, old.getText(R.string.rerun_unavailable_title), "Can't re-run")

        val rebuilt = first.recreate().get()

        val notice = awaitTitled(rebuilt, rebuilt.getText(R.string.rerun_unavailable_title), "Can't re-run again")
        assertEquals(rebuilt.getString(R.string.rerun_photos_missing_body), notice.body())
        assertEquals(1, titledOn(rebuilt, rebuilt.getText(R.string.rerun_unavailable_title)).size)
        assertEquals(
            rebuilt.resources.getQuantityString(R.plurals.selection_count_fmt, 1, 1),
            rebuilt.findViewById<TextView>(R.id.tvSelectionCount).text.toString(),
        )
    }

    @Test
    fun `Can't re-run, for results whose photos are gone, is said again after a rotation`() {
        saved("nophotos", "Results only", data = Data.RESULTS)
        val first = home()
        val old = first.get()
        assertTrue(row(old, "Results only").performLongClick())
        old.findViewById<View>(R.id.btnSelectionRerun).performClick()
        awaitTitled(old, old.getText(R.string.rerun_unavailable_title), "Can't re-run")

        val rebuilt = first.recreate().get()

        val notice = awaitTitled(rebuilt, rebuilt.getText(R.string.rerun_unavailable_title), "Can't re-run again")
        assertEquals(rebuilt.getString(R.string.rerun_photos_missing_body), notice.body())
    }

    @Test
    fun `Pick its reference photo is asked again, and leads on to the reference sheet`() {
        saved("legacy", "Old beam", data = Data.PHOTOS)
        val first = home()
        val old = first.get()
        assertTrue(row(old, "Old beam").performLongClick())
        old.findViewById<View>(R.id.btnSelectionRerun).performClick()
        awaitTitled(old, old.getText(R.string.rerun_pick_reference_title), "Pick its reference photo")

        val rebuilt = first.recreate().get()

        val ask = awaitTitled(rebuilt, rebuilt.getText(R.string.rerun_pick_reference_title), "the question again")
        assertEquals(rebuilt.getString(R.string.rerun_pick_reference_body), ask.body())
        assertTrue(titledOn(old, old.getText(R.string.rerun_pick_reference_title)).isEmpty())
        ask.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idleUntil("the reference sheet") { referenceSheetsOn(rebuilt).isNotEmpty() }
    }

    /** The Re-run's reference sheets ("Reference Image") over [screen]. */
    private fun referenceSheetsOn(screen: HomeActivity) = showingOn(screen, R.id.tvMediaTitle).filter {
        it.findViewById<TextView>(R.id.tvMediaTitle).text.toString() == screen.getString(R.string.reference_image)
    }

    @Test
    fun `the Re-run reference sheet is open again after a rotation`() {
        saved("legacy", "Old beam", data = Data.PHOTOS)
        val first = home()
        val old = first.get()
        assertTrue(row(old, "Old beam").performLongClick())
        old.findViewById<View>(R.id.btnSelectionRerun).performClick()
        awaitTitled(old, old.getText(R.string.rerun_pick_reference_title), "Pick its reference photo")
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idleUntil("the reference sheet") { referenceSheetsOn(old).isNotEmpty() }

        val rebuilt = first.recreate().get()

        idleUntil("the reference sheet on the rebuilt Home") { referenceSheetsOn(rebuilt).isNotEmpty() }
        assertEquals("one sheet, not two", 1, referenceSheetsOn(rebuilt).size)
        assertTrue("the old screen's sheet is closed", referenceSheetsOn(old).isEmpty())
        assertTrue("not the + sheet", showingOn(rebuilt, R.id.tvMediaTitle).size == 1)
    }

    @Test
    fun `the restore question for a backed-up row is asked again, naming the row`() {
        saved("cloudy", "Backed-up beam", cloudId = "c1", syncState = SessionRecord.SyncState.SYNCED)
        val first = home()
        val old = first.get()
        withCloud(old)
        row(old, "Backed-up beam").performClick()
        val title = old.getString(R.string.restore_analysis_title_fmt, "Backed-up beam")
        awaitTitled(old, title, "the restore question")

        val rebuilt = first.recreate().get()

        val ask = awaitTitled(rebuilt, title, "the restore question again")
        assertEquals(rebuilt.getString(R.string.restore_analysis_body), ask.body())
        assertTrue(titledOn(old, title).isEmpty())
    }

    @Test
    fun `Backup failed is asked again with its reason, and Retry still acts on that row`() {
        saved("failed", "Failed beam", data = Data.RESULTS, syncState = SessionRecord.SyncState.FAILED)
        val first = home()
        val old = first.get()
        withCloud(old)
        val badge = { row(old, "Failed beam").findViewById<View>(R.id.sessionState) }
        idleUntil("the backup badge") { badge().visibility == View.VISIBLE }
        badge().performClick()
        awaitTitled(old, old.getText(R.string.cloud_backup_failed_title), "Backup failed")

        val rebuilt = first.recreate().get()

        val ask = awaitTitled(rebuilt, rebuilt.getText(R.string.cloud_backup_failed_title), "Backup failed again")
        assertEquals(rebuilt.getString(R.string.cloud_backup_failed_generic), ask.body())
        assertTrue(titledOn(old, old.getText(R.string.cloud_backup_failed_title)).isEmpty())
        ask.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        // The retry is for that row: it is queued again, or (no backend configured) says why not.
        val queued = rebuilt.getString(R.string.cloud_retry_backup)
        val noBackend = rebuilt.getString(R.string.cloud_backup_no_backend)
        idleUntil("the retry's answer") { ShadowToast.getTextOfLatestToast() in setOf(queued, noBackend) }
        if (ShadowToast.getTextOfLatestToast() == queued) {
            assertEquals(SessionRecord.SyncState.PENDING, SessionStore.get(context, "failed")?.syncState)
        }
    }

    @Test
    fun `the cloud backups checklist is open again with what was unticked`() {
        CloudBackupListing.record(
            context,
            listOf(
                CloudSessionDto(sessionId = "c1", localSessionId = "s1", specimen = "Beam", status = "COMPLETED"),
                CloudSessionDto(sessionId = "c2", localSessionId = "s2", specimen = "Plate", status = "COMPLETED"),
            ),
        )
        val first = home()
        val old = first.get()
        withCloud(old)
        idleUntil("the backups card") { old.findViewById<View>(R.id.homeCloudBackups).visibility == View.VISIBLE }
        old.findViewById<View>(R.id.btnCloudBackupsRestore).performClick()
        val list = awaitTitled(old, old.getText(R.string.cloud_backups_pick_title), "the checklist").listView
        list.performItemClick(list.getChildAt(1), 1, list.getItemIdAtPosition(1))
        assertFalse(list.isItemChecked(1))

        val rebuilt = first.recreate().get()

        val again = awaitTitled(rebuilt, rebuilt.getText(R.string.cloud_backups_pick_title), "the checklist again")
        val ticks: ListView = again.listView
        assertTrue("Beam still ticked", ticks.isItemChecked(0))
        assertFalse("Plate still unticked", ticks.isItemChecked(1))
        assertTrue(again.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertTrue(titledOn(old, old.getText(R.string.cloud_backups_pick_title)).isEmpty())
    }
}
