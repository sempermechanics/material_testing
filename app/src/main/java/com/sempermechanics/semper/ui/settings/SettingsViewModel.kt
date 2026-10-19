package com.sempermechanics.semper.ui.settings

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.cloud.CloudAccountExport
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.session.SessionEverythingExporter
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.ui.common.transfer.RestoreRequest
import com.sempermechanics.semper.ui.common.transfer.request
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Settings' requests that must not die with a rotation (ADR-016): a backup's
 * restore, the product-improvement consent, the two "export my data" exports
 * and a phone-only erase.
 *
 * They used to run on [SettingsActivity]'s own scope, so a rotation while one
 * was in flight cancelled it, or let it land unseen: the row stayed busy with
 * nothing queued, the switch showed an answer the server never confirmed, an
 * export vanished with its banner and nothing to share, an erase was never
 * confirmed. Here they run on [viewModelScope], and what each came to is
 * handed out once ([restoreOutcomes], [consentOutcomes], [exportOutcomes],
 * [localErasures]) to whichever Settings is current. Leaving Settings for
 * good still cancels an export, as the banner's Cancel does.
 */
@MainThread
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    /** A restore of the row [key] (its cloud id), named [name], and what asking for it came to. */
    data class RestoreAsked(val key: String, val name: String, val request: RestoreRequest)

    /** What a consent change came to: [granted] was asked for, and the server took it or not. */
    data class ConsentAnswer(val granted: Boolean, val saved: Boolean)

    private val restoreChannel = Channel<RestoreAsked>(Channel.UNLIMITED)

    /** Each restore request's outcome once; buffered while no Settings is there to take it. */
    val restoreOutcomes: Flow<RestoreAsked> = restoreChannel.receiveAsFlow()

    private val restoring = mutableSetOf<String>()

    /** The rows whose restore is being asked for now, so a recreated screen shows them busy. */
    val restoringKeys: Set<String> get() = restoring.toSet()

    private val _consentSending = MutableStateFlow<Boolean?>(null)

    /** The consent value being sent, or null when none is: the switch shows it, and waits. */
    val consentSending: StateFlow<Boolean?> = _consentSending.asStateFlow()

    private val consentChannel = Channel<ConsentAnswer>(Channel.UNLIMITED)

    /** Each consent change's answer once. */
    val consentOutcomes: Flow<ConsentAnswer> = consentChannel.receiveAsFlow()

    /** The two "export my data" exports: what the cloud holds about the account, and the analyses on this phone. */
    enum class DataExport(val analyticsKind: String) {
        CLOUD("cloud"),
        LOCAL("local"),
    }

    /** An export's progress: [done] of [total] analyses archived. */
    data class ExportProgress(val done: Int, val total: Int)

    /** An export that ended: [file] to share, or null when it produced nothing (a failure). */
    data class ExportFinished(val export: DataExport, val file: File?)

    private val _exports = MutableStateFlow<Map<DataExport, ExportProgress?>>(emptyMap())

    /** The exports running, each with its progress (null before the first tick): the banner shows them. */
    val exports: StateFlow<Map<DataExport, ExportProgress?>> = _exports.asStateFlow()

    private val exportJobs = mutableMapOf<DataExport, Job>()

    private val exportChannel = Channel<ExportFinished>(Channel.UNLIMITED)

    /** Each export's end once; a cancelled one ends with nothing, as the user asked. */
    val exportOutcomes: Flow<ExportFinished> = exportChannel.receiveAsFlow()

    private val erasedChannel = Channel<Unit>(Channel.UNLIMITED)

    /** Each phone-only erase, once it is done. */
    val localErasures: Flow<Unit> = erasedChannel.receiveAsFlow()

    /** How an export is produced; tests stand in for it. Never holds the Activity. */
    @VisibleForTesting
    internal var produceExport: suspend (DataExport, onProgress: (done: Int, total: Int) -> Unit) -> File? =
        { export, onProgress ->
            val app = getApplication<Application>()
            when (export) {
                DataExport.CLOUD -> CloudAccountExport.download(app.cacheDir, SemperApi.get(app))
                DataExport.LOCAL -> SessionEverythingExporter.exportMasterZip(app, onProgress)?.file
            }
        }

    /** How a phone copy is erased; tests stand in for it. */
    @VisibleForTesting
    internal var eraseLocal: suspend (String) -> Unit = { id -> CloudSync.eraseLocalOnly(getApplication(), id) }

    /** How a restore is asked for; tests stand in for it. */
    @VisibleForTesting
    internal var requestRestore: suspend (RestoreStart.Target) -> RestoreRequest =
        { target -> RestoreStart.request(getApplication(), listOf(target)) }

    /** How the consent is sent; tests stand in for it. */
    @VisibleForTesting
    internal var sendConsent: suspend (Boolean) -> Result<Unit> =
        { granted -> AuthRepository(getApplication()).setImprovementConsent(granted) }

    /** Asks for [target]'s restore for the row [key]; the outcome goes to [restoreOutcomes]. */
    fun restore(key: String, target: RestoreStart.Target) {
        restoring += key
        viewModelScope.launch {
            val asked = try {
                requestRestore(target)
            } finally {
                restoring -= key
            }
            restoreChannel.send(RestoreAsked(key, target.name, asked))
        }
    }

    /** Grants or withdraws the improvement consent; one change at a time. */
    fun setImprovementConsent(granted: Boolean) {
        if (_consentSending.value != null) return
        _consentSending.value = granted
        viewModelScope.launch {
            val result = try {
                sendConsent(granted)
            } finally {
                _consentSending.value = null
            }
            result.onFailure { Timber.w(it, "Improvement consent update failed") }
            consentChannel.send(ConsentAnswer(granted, saved = result.isSuccess))
        }
    }

    /**
     * Starts [export], unless it is already running (false). Its progress
     * shows in [exports]; its end, once, in [exportOutcomes]. An empty file
     * counts as a failure: only a file with content is offered to share.
     */
    fun exportData(export: DataExport): Boolean {
        if (export in _exports.value) return false
        val app = getApplication<Application>()
        SemperAnalytics.event(app, SemperAnalytics.EXPORT_STARTED, mapOf("kind" to export.analyticsKind))
        _exports.update { it + (export to null) }
        exportJobs[export] = viewModelScope.launch {
            try {
                val produced = produceExport(export) { done, total ->
                    // From the exporter's thread; a cancelled export's late tick is dropped.
                    _exports.update { if (export in it) it + (export to ExportProgress(done, total)) else it }
                }
                val file = produced?.takeIf { it.exists() && it.length() > 0L }
                val event = if (file == null) SemperAnalytics.EXPORT_FAILED else SemperAnalytics.EXPORT_COMPLETED
                SemperAnalytics.event(app, event, mapOf("kind" to export.analyticsKind))
                _exports.update { it - export }
                exportChannel.send(ExportFinished(export, file))
            } finally {
                _exports.update { it - export }
            }
        }
        return true
    }

    /** Cancels [export] (the banner's Cancel); it ends quietly. */
    fun cancelExport(export: DataExport) {
        exportJobs[export]?.cancel()
    }

    /** Erases the phone's copy of [localSessionId], keeping its backup; [localErasures] says when. */
    fun eraseLocalOnly(localSessionId: String) {
        viewModelScope.launch {
            eraseLocal(localSessionId)
            erasedChannel.send(Unit)
        }
    }

    override fun onCleared() {
        restoreChannel.close()
        consentChannel.close()
        exportChannel.close()
        erasedChannel.close()
        super.onCleared()
    }
}
