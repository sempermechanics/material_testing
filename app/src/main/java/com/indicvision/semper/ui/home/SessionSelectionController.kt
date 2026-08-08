// Selection controller wires a fixed set of named views and one method per
// selection action; both read clearest passed and defined directly.
@file:Suppress("LongParameterList", "TooManyFunctions")

package com.indicvision.semper.ui.home

import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.indicvision.semper.R
import com.indicvision.semper.data.CloudSync
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-select bar for the Home session list: selection set, select-all,
 * rename/delete prompts, and bar visibility. Dialogs use the Activity; list
 * refresh is a callback so the Activity keeps owning cloud reconcile.
 */
class SessionSelectionController(
    private val activity: AppCompatActivity,
    private val adapter: SessionListAdapter,
    private val topBar: android.view.View,
    private val selectionBar: android.view.View,
    private val selectionCount: TextView,
    private val btnSelectionRename: ImageButton,
    private val selectAllBox: MaterialCheckBox,
    private val fab: FloatingActionButton,
    private val backCallback: OnBackPressedCallback,
    private val onRefresh: () -> Unit,
    private val onDeviceOnlyDeleted: () -> Unit = {},
) {
    // Ids rather than indices, so the set survives a refresh() that reorders
    // or drops rows.
    private val selectedIds = linkedSetOf<String>()

    val inSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    fun isSelected(id: String): Boolean = id in selectedIds

    fun selectedRecords(): List<SessionRecord> = adapter.recordsFor(selectedIds)

    fun bindBarActions(
        btnClose: ImageButton,
        btnDelete: ImageButton,
    ) {
        btnClose.setOnClickListener { clearSelection() }
        btnDelete.setOnClickListener { confirmDeleteSelected() }
        // setOnClickListener, not setOnCheckedChangeListener: updateSelectionBar
        // drives the checked state, and a change listener would re-enter here
        // every time it did.
        selectAllBox.setOnClickListener {
            // Unticking means "none", which empties the selection and therefore
            // ends selection mode — the same as clearing it.
            if (selectAllBox.isChecked) selectAll() else clearSelection()
        }
        btnSelectionRename.setOnClickListener {
            selectedRecords().singleOrNull()?.let { promptRename(it) }
        }
    }

    /** Long-press on an unselected list: enters selection mode with that row. */
    fun startSelection(record: SessionRecord) {
        selectedIds.add(record.id)
        adapter.rebindRow(record.id)
        updateSelectionBar()
    }

    /** Toggles one row; entering/leaving selection mode falls out of the count. */
    fun toggleSelection(record: SessionRecord) {
        if (!selectedIds.remove(record.id)) selectedIds.add(record.id)
        adapter.rebindRow(record.id)
        updateSelectionBar()
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        val cleared = selectedIds.toList()
        selectedIds.clear()
        cleared.forEach { adapter.rebindRow(it) }
        updateSelectionBar()
    }

    fun selectAll() {
        // Only the rows that were not already selected change appearance.
        val added = adapter.allIds().filterNot { it in selectedIds }
        selectedIds.addAll(added)
        added.forEach { adapter.rebindRow(it) }
        updateSelectionBar()
    }

    /**
     * Swaps the title row for the contextual bar and keeps the FAB out of the
     * way. Rename needs exactly one target, so it only appears for a single
     * selection.
     */
    fun updateSelectionBar() {
        // Rows can disappear under a selection (a refresh, a delete elsewhere);
        // drop ids that no longer exist so the count never lies.
        selectedIds.retainAll(adapter.allIds().toSet())

        val active = inSelectionMode
        selectionBar.isVisible = active
        topBar.isVisible = !active
        backCallback.isEnabled = active
        if (active) fab.hide() else fab.show()
        selectionCount.text = activity.resources.getQuantityString(
            R.plurals.selection_count_fmt,
            selectedIds.size,
            selectedIds.size,
        )
        btnSelectionRename.isVisible = selectedIds.size == 1
        // Ticked only when every row is in the selection, so the box reports
        // the real state rather than just what was last tapped.
        val allIds = adapter.allIds()
        selectAllBox.isChecked = allIds.isNotEmpty() && selectedIds.size == allIds.size
    }

    /**
     * Bulk delete. Branches on local data + cloud the same way as [confirmDelete].
     */
    fun confirmDeleteSelected() {
        val records = selectedRecords()
        if (records.isEmpty()) return
        if (records.size == 1) {
            confirmDelete(records.first())
            return
        }

        val withLocal = records.filter { it.hasLocalData() }
        val allHaveLocal = withLocal.size == records.size
        val allHaveCloud = records.all { hasCloudCopy(it) }
        val anyCloud = records.any { hasCloudCopy(it) }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(
                activity.resources.getQuantityString(
                    R.plurals.delete_confirm_title_multi,
                    records.size,
                    records.size,
                ),
            )
            .setNegativeButton(R.string.action_cancel, null)

        when {
            allHaveLocal && allHaveCloud -> {
                dialog.setMessage(
                    activity.getString(R.string.delete_confirm_body_cloud_multi, records.size),
                )
                    .setPositiveButton(R.string.delete_cloud_backup) { _, _ ->
                        eraseCloudBackups(records)
                    }
                    .setNeutralButton(R.string.delete_device_only) { _, _ ->
                        eraseSelected(records, cloudToo = false)
                    }
            }
            allHaveLocal && !anyCloud -> {
                dialog.setMessage(R.string.delete_confirm_body_local_multi)
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        eraseSelected(records, cloudToo = true)
                    }
            }
            else -> {
                // Only-cloud stubs and mixed selections: full erase covers every case.
                dialog.setMessage(
                    if (anyCloud) {
                        activity.getString(R.string.delete_confirm_body_cloud_multi, records.count { hasCloudCopy(it) })
                    } else {
                        activity.getString(R.string.delete_confirm_body_local_multi)
                    },
                )
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        eraseSelected(records, cloudToo = true)
                    }
            }
        }
        dialog.show()
    }

    fun promptRename(record: SessionRecord) {
        val input = EditText(activity).apply { setText(record.name) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        SessionStore.rename(activity, record.id, newName)
                        withContext(Dispatchers.Main) {
                            clearSelection()
                            onRefresh()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Delete an analysis. Dual-presence rows offer cloud-backup erase or
     * device-only; only-cloud stubs erase everywhere; local-only deletes fully.
     */
    fun confirmDelete(record: SessionRecord) {
        val hasCloud = hasCloudCopy(record)
        val hasLocal = record.hasLocalData()

        when {
            hasLocal && hasCloud -> {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.delete_confirm_title)
                    .setMessage(R.string.delete_confirm_body_cloud)
                    .setPositiveButton(R.string.delete_cloud_backup) { _, _ -> eraseCloudBackup(record) }
                    .setNeutralButton(R.string.delete_device_only) { _, _ -> eraseDeviceOnly(record) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
            !hasLocal && hasCloud -> {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.delete_confirm_title)
                    .setMessage(R.string.delete_confirm_body_cloud_only)
                    .setPositiveButton(R.string.action_delete) { _, _ -> eraseEverywhere(record) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
            else -> {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.delete_confirm_title)
                    .setMessage(R.string.delete_confirm_body_local)
                    .setPositiveButton(R.string.action_delete) { _, _ -> eraseEverywhere(record) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
    }

    private fun hasCloudCopy(record: SessionRecord): Boolean =
        record.syncState == SessionRecord.SyncState.SYNCED || record.cloudSessionId.isNotBlank()

    private fun eraseDeviceOnly(record: SessionRecord) {
        activity.lifecycleScope.launch {
            CloudSync.eraseLocalOnly(activity, record.id)
            onDeviceOnlyDeleted()
            clearSelection()
            onRefresh()
        }
    }

    private fun eraseCloudBackup(record: SessionRecord) {
        activity.lifecycleScope.launch {
            when (CloudSync.eraseCloudBackup(activity, record)) {
                CloudSync.EraseResult.ERASED_EVERYWHERE ->
                    Toast.makeText(activity, R.string.cloud_delete_backup_done, Toast.LENGTH_SHORT).show()
                CloudSync.EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE ->
                    Toast.makeText(activity, R.string.delete_cloud_failed, Toast.LENGTH_LONG).show()
            }
            clearSelection()
            onRefresh()
        }
    }

    private fun eraseCloudBackups(records: List<SessionRecord>) {
        activity.lifecycleScope.launch {
            var failed = 0
            for (record in records) {
                if (CloudSync.eraseCloudBackup(activity, record) ==
                    CloudSync.EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE
                ) {
                    failed++
                }
            }
            if (failed > 0) {
                Toast.makeText(activity, R.string.delete_cloud_failed, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(activity, R.string.cloud_delete_backup_done, Toast.LENGTH_SHORT).show()
            }
            clearSelection()
            onRefresh()
        }
    }

    private fun eraseSelected(records: List<SessionRecord>, cloudToo: Boolean) {
        activity.lifecycleScope.launch {
            Toast.makeText(
                activity,
                activity.resources.getQuantityString(R.plurals.delete_multi_working, records.size, records.size),
                Toast.LENGTH_SHORT,
            ).show()

            // Sequential, not parallel: each erase is a cloud round-trip, and
            // the backend is happier with one at a time than N at once.
            var stillInCloud = 0
            for (record in records) {
                if (cloudToo) {
                    val result = CloudSync.eraseEverywhere(activity, record.id)
                    if (result == CloudSync.EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE) stillInCloud++
                } else {
                    CloudSync.eraseLocalOnly(activity, record.id)
                }
            }

            // Report what actually happened — never imply a cloud copy is gone
            // when the backend could not be reached.
            val message = if (stillInCloud > 0) {
                activity.resources.getQuantityString(
                    R.plurals.delete_multi_partial,
                    records.size,
                    records.size,
                    stillInCloud,
                )
            } else {
                activity.resources.getQuantityString(R.plurals.delete_multi_done, records.size, records.size)
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            if (!cloudToo) onDeviceOnlyDeleted()

            clearSelection()
            onRefresh()
        }
    }

    private fun eraseEverywhere(record: SessionRecord) {
        activity.lifecycleScope.launch {
            when (CloudSync.eraseEverywhere(activity, record.id)) {
                CloudSync.EraseResult.ERASED_EVERYWHERE ->
                    Toast.makeText(activity, R.string.delete_everywhere_done, Toast.LENGTH_SHORT).show()
                // Nothing was deleted — don't imply the cloud copy is gone.
                CloudSync.EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE ->
                    Toast.makeText(activity, R.string.delete_cloud_failed, Toast.LENGTH_LONG).show()
            }
            clearSelection()
            onRefresh()
        }
    }
}
