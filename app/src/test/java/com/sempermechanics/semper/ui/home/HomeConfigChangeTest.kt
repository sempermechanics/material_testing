package com.sempermechanics.semper.ui.home

import android.app.Activity
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A configuration change (rotation, font scale, dark theme, a resize) rebuilt
 * Home and closed the + sheet, the delete question, the rename dialog and
 * selection mode with it, so a half-made choice was lost (readiness AE1-111).
 * They come back on the rebuilt screen, the rename with what was typed.
 * Home's other questions are in [HomeQuestionsConfigChangeTest].
 */
@RunWith(RobolectricTestRunner::class)
class HomeConfigChangeTest : HomeRecreateFixture() {

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
        saved("s1", "Beam test")
        saved("s2", "Coupon")
        val first = home()
        val old = first.get()
        // The list leads with a day header; the first long-pressable row is an analysis.
        idleUntil("an analysis row") { rows(old).isNotEmpty() }
        assertTrue(rows(old).first().performLongClick())
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

    /** The rows marked selected (their tick showing) on [screen], by title. */
    private fun selectedRows(screen: Activity): List<String> = rows(screen)
        .filter { it.findViewById<View>(R.id.sessionCheck).visibility == View.VISIBLE }
        .map { it.findViewById<TextView>(R.id.sessionTitle).text.toString() }
        .sorted()

    @Test
    fun `two selected rows are still selected after a rotation`() {
        saved("s1", "Beam test")
        saved("s2", "Coupon")
        saved("s3", "Plate")
        val first = home()
        val old = first.get()
        assertTrue(row(old, "Beam test").performLongClick())
        assertTrue(row(old, "Plate").performClick())
        idleUntil("two rows selected") { selectedRows(old).size == 2 }

        val rebuilt = first.recreate().get()

        idleUntil("the selection bar") { rebuilt.findViewById<View>(R.id.homeSelectionBar).visibility == View.VISIBLE }
        assertEquals(
            rebuilt.resources.getQuantityString(R.plurals.selection_count_fmt, 2, 2),
            rebuilt.findViewById<TextView>(R.id.tvSelectionCount).text.toString(),
        )
        idleUntil("the same two rows selected") { selectedRows(rebuilt) == listOf("Beam test", "Plate") }
    }

    /** The rename dialogs showing over [activity]: the ones whose content is an [EditText]. */
    private fun renameFieldsOn(activity: Activity): List<EditText> = dialogsOn(activity)
        .mapNotNull { dialog -> dialog.findViewById<View>(android.R.id.content)?.findEditText() }

    private fun View.findEditText(): EditText? = when (this) {
        is EditText -> this
        is android.view.ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findEditText() }
        else -> null
    }

    @Test
    fun `the rename dialog survives a rotation with the name typed so far`() {
        saved("s1", "Beam test")
        val first = home()
        val old = first.get()
        idleUntil("an analysis row") { rows(old).isNotEmpty() }
        assertTrue(rows(old).first().performLongClick())
        old.findViewById<View>(R.id.btnSelectionRename).performClick()
        idleUntil("the rename dialog") { renameFieldsOn(old).isNotEmpty() }
        renameFieldsOn(old).single().apply {
            setText("Beam test, second pull")
            setSelection(5, 9)
        }

        val rebuilt = first.recreate().get()

        idleUntil("the rename dialog on the rebuilt Home") { renameFieldsOn(rebuilt).isNotEmpty() }
        val field = renameFieldsOn(rebuilt).single()
        assertEquals("Beam test, second pull", field.text.toString())
        assertEquals(5, field.selectionStart)
        assertEquals(9, field.selectionEnd)
        assertTrue("the old screen's dialog is closed", renameFieldsOn(old).isEmpty())
        assertEquals(
            rebuilt.resources.getQuantityString(R.plurals.selection_count_fmt, 1, 1),
            rebuilt.findViewById<TextView>(R.id.tvSelectionCount).text.toString(),
        )
    }
}
