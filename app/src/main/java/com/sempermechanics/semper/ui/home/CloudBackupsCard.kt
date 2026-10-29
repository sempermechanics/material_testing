// The card's four views, its two actions and its checklist's reopen after a
// configuration change (readiness AE1-111) are its constructor's parameters.
@file:Suppress("LongParameterList")

package com.sempermechanics.semper.ui.home

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.CloudBackupListing
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.ui.common.ByteSize
import com.sempermechanics.semper.ui.common.SerialJob
import com.sempermechanics.semper.ui.common.dialog.ReopenAfterRecreate
import kotlinx.coroutines.CoroutineScope

/**
 * The card above Home's list that offers backups this phone has no row for:
 * made on another phone, or before a reinstall. Without it a new phone opened
 * on "No analyses yet" while the account had backups, which only Settings
 * listed.
 *
 * **Restore** opens a checklist (all ticked) and hands the ticked backups to
 * [onRestore]; **Hide** hands them all to [onHide]. The host decides what is
 * offered (see [CloudBackupListing.offered]) and calls [show].
 *
 * [reopen] brings the checklist back, with its ticks, when a configuration
 * change rebuilds the screen (readiness AE1-111); build the card after
 * `super.onCreate`.
 */
internal class CloudBackupsCard(
    private val card: View,
    private val text: TextView,
    restoreButton: View,
    hideButton: View,
    private val onRestore: (List<RestoreStart.Target>) -> Unit,
    private val onHide: (List<CloudBackupListing.Backup>) -> Unit,
    private val reopen: ReopenAfterRecreate? = null,
) {
    private val context: Context get() = card.context
    private var offered: List<CloudBackupListing.Backup> = emptyList()

    /** The backups the replaced screen's checklist had unticked: shown again once the offer is read. */
    private var reopenUnticked: Set<String>? =
        reopen?.restored()?.let { state -> state.getStringArrayList(UNTICKED).orEmpty().toSet() }

    /** The read in flight ([refresh]); a newer one replaces it. */
    private val reads = SerialJob()

    init {
        restoreButton.setOnClickListener { pickAndRestore() }
        hideButton.setOnClickListener { if (offered.isNotEmpty()) onHide(offered) }
    }

    /**
     * Reads what to offer with [read] in [scope] and [show]s it, then hands it
     * to [onShown]. Latest wins: a newer call cancels a read still in flight,
     * so an older listing never lands after a newer one.
     */
    fun refresh(
        scope: CoroutineScope,
        read: suspend () -> List<CloudBackupListing.Backup>,
        onShown: (List<CloudBackupListing.Backup>) -> Unit,
    ) {
        reads.launch(scope) {
            val backups = read()
            show(backups)
            onShown(backups)
        }
    }

    fun show(backups: List<CloudBackupListing.Backup>) {
        offered = backups
        card.isVisible = backups.isNotEmpty()
        if (backups.isEmpty()) return
        text.text = context.resources.getQuantityString(R.plurals.cloud_backups_banner, backups.size, backups.size)
        reopenUnticked?.let { unticked ->
            reopenUnticked = null
            pickAndRestore(unticked)
        }
    }

    /** The checklist of what is [offered], all ticked but [unticked] (cloud ids). */
    private fun pickAndRestore(unticked: Set<String> = emptySet()) {
        val backups = offered
        if (backups.isEmpty()) return
        val ticked = BooleanArray(backups.size) { backups[it].cloudId !in unticked }
        val labels = backups.map { label(context, it) }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.cloud_backups_pick_title)
            .setMultiChoiceItems(labels, ticked) { d, which, isChecked ->
                ticked[which] = isChecked
                (d as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = ticked.any { it }
            }
            .setPositiveButton(R.string.restore_action) { _, _ ->
                onRestore(backups.filterIndexed { i, _ -> ticked[i] }.map { target(context, it) })
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = ticked.any { it }
        reopen?.keep(dialog) {
            val left = backups.filterIndexed { i, _ -> !ticked[i] }.map { it.cloudId }
            Bundle().apply { putStringArrayList(UNTICKED, ArrayList(left)) }
        }
    }

    companion object {
        private const val UNTICKED = "unticked"

        /** A backup's name, falling back to a plain description when it has none. */
        fun nameOf(context: Context, backup: CloudBackupListing.Backup): String =
            backup.name.ifBlank { context.getString(R.string.cloud_backups_unnamed) }

        fun label(context: Context, backup: CloudBackupListing.Backup): String = context.getString(
            R.string.cloud_backups_item_fmt,
            nameOf(context, backup),
            ByteSize.format(backup.bytes),
        )

        /** Restore into the backup's own local id, as Settings does, so a later restore fills the same row. */
        fun target(context: Context, backup: CloudBackupListing.Backup) =
            RestoreStart.targetFor(backup.cloudId, backup.localId, nameOf(context, backup))
    }
}
