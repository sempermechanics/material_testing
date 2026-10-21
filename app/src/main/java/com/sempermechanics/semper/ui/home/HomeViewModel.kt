package com.sempermechanics.semper.ui.home

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.cloud.CloudBackupListing
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.SessionDeletes
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.ui.common.SerialJob
import com.sempermechanics.semper.ui.common.transfer.RestoreRequest
import com.sempermechanics.semper.ui.common.transfer.request
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home's work that must not die with a rotation (ADR-016): the phone's list
 * as last read, the rows a queued delete hides, the backups on offer, and the
 * restores and phone erases Home asks for.
 *
 * A restore used to run on the Activity's scope: a rotation during its cloud
 * lookup cancelled it after the selection had already been cleared, so the
 * tap did nothing. A multi-row erase did the same part-way through, with no
 * toast. Both run on [viewModelScope] here, and what each came to is handed
 * out once ([restoreOutcomes], [eraseOutcomes]) to whichever Home is current.
 */
@MainThread
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    /** One read of the phone's list: its rows and which of them have no frames here. */
    data class HomeList(
        val sessions: List<SessionRecord>,
        val withoutLocalData: Set<String>,
        /** Counts reads, so a read equal to the last one is still shown (the cards re-read too). */
        val generation: Long,
    )

    /** What one erase from [eraseLocally] came to. Plain data: no screen is held. */
    data class LocalErase(
        val records: List<SessionRecord>,
        /** Only the phone copies went; every backup stays. */
        val deviceOnly: Boolean,
        /** Rows kept because a cloud copy they may have could not be erased (0 when [deviceOnly]). */
        val kept: Int,
    )

    private val _list = MutableStateFlow<HomeList?>(null)

    /** The latest list read; null before the first. */
    val list: StateFlow<HomeList?> = _list.asStateFlow()

    /** Rows just queued for deletion, hidden until WorkManager lists their job or it ends. */
    private val justQueuedDeletes = mutableSetOf<String>()

    /** The list read in flight ([refreshList]); a newer one replaces it. */
    private val listRead = SerialJob()
    private var generation = 0L

    private val restoreChannel = Channel<RestoreRequest>(Channel.UNLIMITED)

    /** What each restore request came to, once each; buffered while no Home is there to take it. */
    val restoreOutcomes: Flow<RestoreRequest> = restoreChannel.receiveAsFlow()

    private val eraseChannel = Channel<LocalErase>(Channel.UNLIMITED)

    /** What each erase came to, once each; buffered while no Home is there to take it. */
    val eraseOutcomes: Flow<LocalErase> = eraseChannel.receiveAsFlow()

    /** Where the disk reads run; tests hold them. */
    @VisibleForTesting
    internal var io: CoroutineDispatcher = Dispatchers.IO

    /** How a batch of restores is asked for; tests stand in for it. */
    @VisibleForTesting
    internal var requestRestore: suspend (List<RestoreStart.Target>) -> RestoreRequest =
        { targets -> RestoreStart.request(getApplication(), targets, io) }

    /** How one row is erased with any backup it has; tests stand in for it. */
    @VisibleForTesting
    internal var eraseEverywhere: suspend (String) -> CloudSync.EraseOutcome =
        { id -> CloudSync.eraseEverywhere(getApplication(), id) }

    /** How one row's phone copy is dropped, keeping its backup; tests stand in for it. */
    @VisibleForTesting
    internal var eraseLocalOnly: suspend (String) -> Unit =
        { id -> CloudSync.eraseLocalOnly(getApplication(), id) }

    /**
     * Re-reads the phone's list. Latest wins: a newer call cancels the read
     * still in flight, so an older list can never land after a newer one
     * (refresh runs on resume, on every finished transfer, delete, rename
     * and restore, often several within a second).
     */
    fun refreshList() {
        listRead.launch(viewModelScope) {
            val read = readList()
            generation++
            _list.value = read.copy(generation = generation)
        }
    }

    /** Suspends until the list read in flight, if any, has landed. */
    suspend fun awaitListRead() = listRead.join()

    /** Hides [ids] from the list until [clearQueuedDeletes]. */
    fun hideQueuedDeletes(ids: Collection<String>) {
        justQueuedDeletes += ids
    }

    /** The queued deletes have reached WorkManager (or ended): their own state shows from here. */
    fun clearQueuedDeletes() {
        justQueuedDeletes.clear()
    }

    /**
     * The backups this phone has no row for, from the listing the last
     * reconcile saved. Demo accounts have no restore, so they are offered nothing.
     */
    suspend fun offeredBackups(): List<CloudBackupListing.Backup> {
        val app = getApplication<Application>()
        if (!LicenseEntitlements.cloudBackupEnabled(app)) return emptyList()
        return withContext(io) { CloudBackupListing.offered(app) }
    }

    /** Restores [records] into their own rows, each looked up in the cloud first when it has no link. */
    fun restoreRows(records: List<SessionRecord>) {
        val app = getApplication<Application>()
        restore {
            records.map { record ->
                RestoreStart.Target(CloudSync.resolveCloudIdFor(app, record).orEmpty(), record.id, record.name)
            }
        }
    }

    /**
     * Resolves [targets] (which may look them up over the network), asks for
     * them, and hands the outcome to [restoreOutcomes]. Survives a rotation.
     */
    fun restore(targets: suspend () -> List<RestoreStart.Target>) {
        viewModelScope.launch {
            val batch = targets()
            if (batch.isEmpty()) return@launch
            restoreChannel.send(requestRestore(batch))
        }
    }

    /**
     * Erases [records] one by one and hands the outcome to [eraseOutcomes].
     * [deviceOnly] drops the phone copies and keeps the backups; otherwise the
     * rows have no backup the phone knows of and go entirely. Survives a rotation.
     */
    fun eraseLocally(records: List<SessionRecord>, deviceOnly: Boolean) {
        viewModelScope.launch {
            // A row whose backup is still being made can have a cloud copy the
            // phone does not know of yet; eraseEverywhere looks, and keeps the row
            // when it cannot.
            var kept = 0
            for (record in records) {
                if (deviceOnly) {
                    eraseLocalOnly(record.id)
                } else if (eraseEverywhere(record.id) != CloudSync.EraseOutcome.ERASED_EVERYWHERE) {
                    kept++
                }
            }
            eraseChannel.send(LocalErase(records, deviceOnly, kept))
        }
    }

    private suspend fun readList(): HomeList {
        val app = getApplication<Application>()
        val justQueued = justQueuedDeletes.toSet()
        return withContext(io) {
            val hidden = justQueued + SessionDeletes.pendingRowIds(app)
            val sessions = SessionStore.list(app).filterNot { it.id in hidden }
            // Read here, so binding, selecting and opening a row never touch the disk.
            val withoutLocalData = sessions.filterNot { it.hasLocalData() }.map { it.id }.toSet()
            HomeList(sessions, withoutLocalData, generation = 0L)
        }
    }

    override fun onCleared() {
        restoreChannel.close()
        eraseChannel.close()
        super.onCleared()
    }
}
