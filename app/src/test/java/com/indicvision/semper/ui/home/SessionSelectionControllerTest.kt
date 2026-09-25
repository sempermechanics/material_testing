package com.indicvision.semper.ui.home

import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.indicvision.semper.R
import com.indicvision.semper.data.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Home's multi-select: the selection set, the bar it swaps in for the title
 * row, and which delete prompt a selection gets. The prompt matters most — it
 * is where a cloud backup and a phone copy are told apart, and offering the
 * wrong one deletes the wrong copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionSelectionControllerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var activity: AppCompatActivity
    private lateinit var controller: SessionSelectionController
    private lateinit var adapter: SessionListAdapter

    private lateinit var topBar: View
    private lateinit var selectionBar: View
    private lateinit var count: TextView
    private lateinit var rename: ImageButton
    private lateinit var selectAll: MaterialCheckBox
    private lateinit var fab: ImageButton
    private lateinit var close: ImageButton
    private lateinit var delete: ImageButton
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }
    private var refreshes = 0
    private var deviceOnlyDeletes = 0

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper) // Material dialogs need the app theme
        activity = built.setup().get()
        adapter = SessionListAdapter(isSelected = { controller.isSelected(it) }, onClick = {}, onLongClick = {})
        topBar = View(activity)
        selectionBar = View(activity).apply { visibility = View.GONE }
        count = TextView(activity)
        rename = ImageButton(activity)
        selectAll = MaterialCheckBox(activity)
        fab = ImageButton(activity)
        close = ImageButton(activity)
        delete = ImageButton(activity)
        controller = SessionSelectionController(
            activity = activity,
            adapter = adapter,
            topBar = topBar,
            selectionBar = selectionBar,
            selectionCount = count,
            btnSelectionRename = rename,
            selectAllBox = selectAll,
            fab = fab,
            backCallback = back,
            onRefresh = { refreshes++ },
            onDeviceOnlyDeleted = { deviceOnlyDeletes++ },
        )
        controller.bindBarActions(btnClose = close, btnDelete = delete)
    }

    /** [local] gives the record a session dir holding a `.dat`, so it has phone data. */
    private fun record(id: String, local: Boolean = true, cloud: Boolean = false): SessionRecord {
        val dir = File(temp.root, id).apply { mkdirs() }
        if (local) File(dir, "frame_0000.dat").writeBytes(ByteArray(32))
        return SessionRecord(
            id = id,
            name = "Specimen $id",
            createdAt = 0L,
            updatedAt = 0L,
            frameCount = 1,
            subset = 41,
            step = 5,
            strainWindow = 15,
            imgW = 100,
            imgH = 100,
            roiX = 0,
            roiY = 0,
            roiW = 100,
            roiH = 100,
            refPath = File(dir, "ref.png").path,
            refName = "ref.png",
            sessionDir = dir.path,
            cloudSessionId = if (cloud) "cloud-$id" else "",
            syncState = if (cloud) SessionRecord.SyncState.SYNCED else SessionRecord.SyncState.LOCAL_ONLY,
        )
    }

    private val a by lazy { record("a") }
    private val b by lazy { record("b") }
    private val c by lazy { record("c") }

    private fun list(vararg records: SessionRecord) = adapter.submit(records.toList())

    private fun latestDialog(): Dialog = ShadowDialog.getLatestDialog()

    private fun dialogMessage(): String? =
        latestDialog().findViewById<TextView>(android.R.id.message)?.text?.toString()

    // ── Selection set and bar ────────────────────────────────────────────────

    @Test
    fun `a long press enters selection mode and swaps in the bar`() {
        list(a, b)
        controller.startSelection(a)

        assertTrue(controller.inSelectionMode)
        assertEquals(View.VISIBLE, selectionBar.visibility)
        assertEquals(View.GONE, topBar.visibility)
        assertEquals("the FAB steps aside", View.GONE, fab.visibility)
        assertTrue("back leaves selection first", back.isEnabled)
        assertEquals("1 selected", count.text.toString())
        assertEquals("rename needs exactly one", View.VISIBLE, rename.visibility)
    }

    @Test
    fun `a second row hides rename, and emptying the set leaves selection mode`() {
        list(a, b)
        controller.startSelection(a)
        controller.toggleSelection(b)
        assertEquals("2 selected", count.text.toString())
        assertEquals(View.GONE, rename.visibility)

        controller.toggleSelection(a)
        controller.toggleSelection(b)
        assertFalse(controller.inSelectionMode)
        assertEquals(View.GONE, selectionBar.visibility)
        assertEquals(View.VISIBLE, topBar.visibility)
        assertEquals(View.VISIBLE, fab.visibility)
        assertFalse(back.isEnabled)
    }

    @Test
    fun `the select-all box is ticked only when every row is selected`() {
        list(a, b, c)
        controller.startSelection(a)
        assertFalse(selectAll.isChecked)

        selectAll.performClick() // unticked → ticked: select all
        assertTrue(selectAll.isChecked)
        assertEquals(listOf("a", "b", "c"), controller.selectedRecords().map { it.id })

        controller.toggleSelection(b)
        assertFalse("one row out: the box reports it", selectAll.isChecked)
    }

    @Test
    fun `unticking select-all clears the selection`() {
        list(a, b)
        controller.selectAll()
        selectAll.performClick() // ticked → unticked
        assertFalse(controller.inSelectionMode)
    }

    @Test
    fun `the close button clears the selection`() {
        list(a, b)
        controller.selectAll()
        close.performClick()
        assertFalse(controller.inSelectionMode)
        assertFalse(controller.isSelected("a"))
    }

    @Test
    fun `rows that vanish under a selection drop out of the count`() {
        list(a, b, c)
        controller.selectAll()
        list(a, c) // a refresh lost b
        controller.updateSelectionBar()

        assertEquals("2 selected", count.text.toString())
        assertFalse(controller.isSelected("b"))
        assertTrue(selectAll.isChecked)
    }

    @Test
    fun `selection survives a refresh that reorders rows`() {
        list(a, b, c)
        controller.startSelection(b)
        list(c, b, a)
        controller.updateSelectionBar()
        assertEquals(listOf("b"), controller.selectedRecords().map { it.id })
    }

    // ── Delete prompts ───────────────────────────────────────────────────────

    @Test
    fun `a phone-only analysis gets the permanent local prompt`() {
        list(a)
        controller.confirmDelete(a)
        assertEquals(activity.getString(R.string.delete_confirm_body_local), dialogMessage())
    }

    @Test
    fun `a cloud-only stub gets the erase-from-cloud prompt`() {
        val stub = record("s", local = false, cloud = true)
        list(stub)
        controller.confirmDelete(stub)
        assertEquals(activity.getString(R.string.delete_confirm_body_cloud_only), dialogMessage())
    }

    @Test
    fun `an analysis on both offers device-only or cloud-only removal`() {
        val both = record("d", cloud = true)
        list(both)
        controller.confirmDelete(both)
        val dialog = latestDialog()

        assertEquals(
            activity.getString(R.string.delete_confirm_body_cloud),
            dialog.findViewById<TextView>(R.id.tvDeleteMessage).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.delete_device_only),
            dialog.findViewById<MaterialButton>(R.id.btnDeleteLeft).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.delete_cloud_backup),
            dialog.findViewById<MaterialButton>(R.id.btnDeleteMid).text.toString(),
        )
    }

    @Test
    fun `the delete button with one row selected asks about that row`() {
        list(a, b)
        controller.startSelection(b)
        delete.performClick()
        assertEquals(activity.getString(R.string.delete_confirm_body_local), dialogMessage())
    }

    @Test
    fun `several phone-only rows get one plural prompt`() {
        list(a, b)
        controller.selectAll()
        delete.performClick()
        assertEquals(activity.getString(R.string.delete_confirm_body_local_multi), dialogMessage())
    }

    @Test
    fun `a mixed selection counts only the rows that are backed up`() {
        val cloudRow = record("d", cloud = true)
        list(a, cloudRow)
        controller.selectAll()
        delete.performClick()
        assertEquals(activity.getString(R.string.delete_confirm_body_cloud_multi, 1), dialogMessage())
    }

    @Test
    fun `nothing selected, nothing asked`() {
        list(a)
        controller.confirmDeleteSelected()
        assertNull(ShadowDialog.getLatestDialog())
    }

    @Test
    fun `deleting the phone copy keeps the cloud one, then clears and refreshes`() {
        val both = record("d", cloud = true)
        list(both)
        controller.startSelection(both)
        delete.performClick()
        latestDialog().findViewById<MaterialButton>(R.id.btnDeleteLeft).performClick()
        pumpUntil { refreshes > 0 }

        assertEquals(1, deviceOnlyDeletes)
        assertFalse(controller.inSelectionMode)
    }

    // ── Rename ───────────────────────────────────────────────────────────────

    @Test
    fun `rename is offered prefilled with the current name`() {
        list(a)
        controller.startSelection(a)
        rename.performClick()

        val input = findEditText(latestDialog().window!!.decorView)
        assertEquals("Specimen a", input.text.toString())
    }

    @Test
    fun `a blank rename is ignored`() {
        list(a)
        controller.startSelection(a)
        rename.performClick()
        val dialog = latestDialog() as androidx.appcompat.app.AlertDialog
        findEditText(dialog.window!!.decorView).setText("   ")
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, refreshes)
        assertTrue("still selected", controller.isSelected("a"))
    }

    private fun findEditText(root: View): EditText {
        if (root is EditText) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                runCatching { return findEditText(root.getChildAt(i)) }
            }
        }
        error("no EditText under $root")
    }

    /** The erase runs on Dispatchers.IO and hands back to main, so pump both. */
    private fun pumpUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }
}
