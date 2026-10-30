package com.sempermechanics.semper.data.cloud.sync

import com.sempermechanics.semper.data.cloud.CloudBackupListing
import com.sempermechanics.semper.data.session.SessionRecord

/**
 * What the sync from the account's other phones decides, with no Android in
 * it: whether a run may go ahead, and which backups it restores.
 *
 * A backup is restored only when **no row on this phone** claims it, by its
 * local id or by the cloud id a row stored ([CloudBackupListing.notOnPhone],
 * the rule Home's card uses). That leaves alone:
 *
 * - an analysis removed from this phone and kept in the cloud: its row stays
 *   ("Only in cloud"), so it is never pulled back down on its own;
 * - a restore already queued or running: its row is written before it is
 *   queued, and its cloud id is in `restoring` besides;
 * - an analysis being deleted: its row stays until its cloud copy is gone.
 *
 * It also skips what the user hid from Home's card, and every backup the sync
 * has started once before (`tried`). A restore that failed, or that the user
 * cancelled, is then not retried every few hours; Home's card still offers it.
 */
object PhoneSync {

    /** Why a run does nothing. */
    enum class Skip {
        /** "Download from my other phones" is off. */
        SWITCHED_OFF,

        /** The licence has no restore, or the restore kill switch or a required update holds it. */
        RESTORE_OFF,

        /** The phone's analysis list cannot be read, so every backup would look missing. */
        INDEX_UNREADABLE,
    }

    /** Why a run with these inputs does nothing, or null when it may go ahead. Checked in this order. */
    fun skip(switchOn: Boolean, restoreEnabled: Boolean, indexReadable: Boolean): Skip? = when {
        !switchOn -> Skip.SWITCHED_OFF
        !restoreEnabled -> Skip.RESTORE_OFF
        !indexReadable -> Skip.INDEX_UNREADABLE
        else -> null
    }

    /**
     * The backups of [listed] to restore, in listing order.
     *
     * @param listed the account's finished backups for this app.
     * @param records every row on this phone.
     * @param hidden cloud ids hidden from Home's card.
     * @param tried cloud ids this sync has started a restore of before.
     * @param restoring cloud ids with a restore queued or running.
     */
    fun toRestore(
        listed: List<CloudBackupListing.Backup>,
        records: List<SessionRecord>,
        hidden: Set<String>,
        tried: Set<String>,
        restoring: Set<String>,
    ): List<CloudBackupListing.Backup> {
        val skipped = hidden + tried + restoring
        return CloudBackupListing.notOnPhone(listed, records)
            .filter { it.cloudId.isNotBlank() && it.cloudId !in skipped }
            .distinctBy { it.cloudId }
    }

    /**
     * What to remember as tried after a run: the earlier [tried] that are still
     * [listed] (a backup deleted since needs no memory), plus [started].
     */
    fun triedAfter(
        tried: Set<String>,
        listed: List<CloudBackupListing.Backup>,
        started: Collection<String>,
    ): Set<String> {
        val ids = listed.mapTo(HashSet()) { it.cloudId }
        return tried.filterTo(HashSet()) { it in ids } + started
    }
}
