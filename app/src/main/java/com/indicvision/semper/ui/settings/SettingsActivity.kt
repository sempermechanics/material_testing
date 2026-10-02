// Settings Activity hosts cloud backup and per-analysis restore/download/delete.
// Account, storage, preferences, your-data, and help live in section classes.

@file:Suppress("TooManyFunctions", "ReturnCount")

package com.indicvision.semper.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.R
import com.indicvision.semper.data.account.AuthRepository
import com.indicvision.semper.data.account.LicenseEntitlements
import com.indicvision.semper.data.account.LicenseErrors
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.cloud.SessionDeletes
import com.indicvision.semper.data.cloud.TransferWork
import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.data.cloud.restore.RestoreFailureLedger
import com.indicvision.semper.data.cloud.restore.RestoreStart
import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.databinding.ActivitySettingsBinding
import com.indicvision.semper.databinding.SettingsScrollContentBinding
import com.indicvision.semper.databinding.SettingsSectionHeaderBinding
import com.indicvision.semper.ui.auth.AuthActivity
import com.indicvision.semper.ui.common.AuthRoute
import com.indicvision.semper.ui.common.ByteSize
import com.indicvision.semper.ui.common.ConflatedRefresh
import com.indicvision.semper.ui.common.CrispToast
import com.indicvision.semper.ui.common.DeleteChoiceDialog
import com.indicvision.semper.ui.common.DeleteFeedback
import com.indicvision.semper.ui.common.Dialogs
import com.indicvision.semper.ui.common.ExternalLinks
import com.indicvision.semper.ui.common.Feedback
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.Motion
import com.indicvision.semper.ui.common.SettingsSectionHeader
import com.indicvision.semper.ui.common.SignOutRun
import com.indicvision.semper.ui.common.TransferBannerController
import com.indicvision.semper.ui.common.TransferWorkObserver
import com.indicvision.semper.ui.common.confirm
import com.indicvision.semper.ui.home.SessionOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Settings: account, cloud preferences, per-analysis data management, data
 * export/erasure and analysis defaults. A page rather than a sheet — Home
 * refreshes in `onResume`, so anything changed here is reflected on return.
 */
@MainThread
class SettingsActivity : AppCompatActivity() {

    /**
     * Registered up front, as activity-result launchers must be: the delete flow
     * hands off to the sign-in screen and only proceeds if it answers OK.
     */
    private val reauthLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) yourDataSection.deleteAccount()
    }

    /**
     * Analyses Download: pick the destination document first, then enqueue the
     * background write. [CreateDocument] is the location confirmation.
     */
    private val createDownloadDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(ZIP_MIME),
    ) { uri ->
        val pending = pendingDownload
        pendingDownload = null
        if (uri == null || pending == null) return@registerForActivityResult
        startBundleDownloadToUri(pending, uri)
    }

    /** Stashed while the SAF save-as picker is open. */
    private var pendingDownload: PendingBundleDownload? = null

    private lateinit var binding: ActivitySettingsBinding

    /** The scroll content's sections ([SettingsScrollContentView.sections]). */
    private lateinit var views: SettingsScrollContentBinding
    private lateinit var analysesAdapter: AnalysisDataAdapter

    /** Restore / Save-to-Files download keys currently busy — disables row actions. */
    private val busy = BusyTransfers()

    internal lateinit var transferBanner: TransferBannerController
    private lateinit var yourDataSection: SettingsYourDataSection
    private lateinit var deleteFeedback: DeleteFeedback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDownload = PendingBundleDownload.fromBundle(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        views = binding.settingsSections.sections
        yourDataSection = SettingsYourDataSection(this, views)
        // Edge-to-edge: without this the status bar swallows taps on the back arrow.
        Insets.padTop(binding.settingsTopBar)
        Insets.padBottom(binding.settingsScroll)
        transferBanner = TransferBannerController(binding.transferBannerRoot.root)

        binding.btnSettingsBack.setOnClickListener { finish() }

        analysesAdapter = AnalysisDataAdapter(
            stateLine = ::stateLine,
            backupLabel = ::backupLabel,
            onOpen = ::openOrDownloadAnalysis,
            onBackup = ::startBackup,
            onLocalDownload = ::confirmLocalDownload,
            onCloudRestore = ::confirmCloudRestore,
            onDelete = ::deleteBackup,
        )
        views.analysesDataList.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = analysesAdapter
        }

        wireCollapsible(views.headerAccount, R.string.account_section, views.bodyAccount)
        wireCollapsible(views.headerStorage, R.string.storage_section, views.bodyStorage)
        wireCollapsible(views.headerYourData, R.string.your_data_section, views.bodyYourData)
        wireCollapsible(views.headerAnalysisPrefs, R.string.analysis_preferences, views.bodyAnalysisPrefs)
        wireCollapsible(views.headerHelpSupport, R.string.help_support_section, views.bodyHelpSupport)
        wireCollapsible(views.headerCloud, R.string.cloud_section, views.bodyCloud)
        wireCollapsible(views.headerAnalysesData, R.string.analyses_data_management, views.bodyAnalysesData)

        SettingsAccountSection(this, views).wire()
        // Backup and restore are the licensed half of cloud. A demo account
        // records its analyses silently and cannot pull them back, so both
        // sections are absent rather than shown disabled.
        if (LicenseEntitlements.cloudBackupEnabled(this)) {
            observeTransfers()
            deleteFeedback = DeleteFeedback(this, binding.settingsRoot) { wireAnalysesDataSection() }
            deleteFeedback.observe()
            wireCloudSection()
            wireAnalysesDataSection()
        } else {
            listOf(views.headerCloud.root, views.bodyCloud, views.headerAnalysesData.root, views.bodyAnalysesData)
                .forEach { it.isVisible = false }
        }
        SettingsStorageSection(this, views).wire()
        yourDataSection.wire()
        SettingsPreferencesSection(this, views).wire()
        SettingsHelpSupportSection(this, views).wire()
        wireFooter()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingDownload?.writeTo(outState)
    }

    /**
     * Titles [header] and makes it open and close [body], starting closed.
     * The chevron is the header's own, never one looked up across the screen.
     */
    private fun wireCollapsible(header: SettingsSectionHeaderBinding, @StringRes title: Int, body: View) {
        SettingsSectionHeader.bind(header, title)
        val chevron = header.ivSectionChevron
        fun apply(expanded: Boolean) {
            body.isVisible = expanded
            chevron.rotation = if (expanded) CHEVRON_EXPANDED_DEG else 0f
        }
        apply(false)
        header.root.setOnClickListener {
            Motion.animateExpandCollapse(binding.settingsSections)
            apply(!body.isVisible)
        }
    }

    // ── Cloud backup ─────────────────────────────────────────────────────

    private fun wireCloudSection() {
        val switchSave = views.switchSaveCloud
        val switchWifi = views.switchWifiOnly
        val sub = views.tvSaveCloudSub
        val status = views.tvCloudSyncStatus

        switchSave.isChecked = DicSettings.saveToCloud(this)
        switchWifi.isChecked = DicSettings.uploadWifiOnly(this)
        sub.setText(if (switchSave.isChecked) R.string.setting_save_cloud_sub else R.string.setting_save_cloud_sub_off)

        switchSave.setOnCheckedChangeListener { _, checked ->
            DicSettings.setSaveToCloud(this, checked)
            sub.setText(if (checked) R.string.setting_save_cloud_sub else R.string.setting_save_cloud_sub_off)
            if (checked) maybeOfferBackfill()
        }
        switchWifi.setOnCheckedChangeListener { _, checked -> DicSettings.setUploadWifiOnly(this, checked) }

        lifecycleScope.launch {
            val states = withContext(Dispatchers.IO) {
                SessionStore.list(this@SettingsActivity).map { it.syncState }
            }
            status.text = BackupStatus.text(resources, states)
        }
    }

    private fun maybeOfferBackfill() {
        if (!IndicApi.get(this).enabled) return
        lifecycleScope.launch {
            val localOnly = withContext(Dispatchers.IO) {
                SessionStore.list(this@SettingsActivity)
                    .filter { it.syncState == SessionRecord.SyncState.LOCAL_ONLY }
            }
            if (localOnly.isEmpty()) return@launch
            Dialogs.confirm(
                this@SettingsActivity,
                getText(R.string.cloud_backfill_title),
                resources.getQuantityString(R.plurals.cloud_backfill_body, localOnly.size, localOnly.size),
                R.string.cloud_backfill_confirm,
            ) {
                localOnly.forEach { CloudSync.enqueueUpload(this@SettingsActivity, it.id) }
            }
        }
    }

    // ── Analyses data management ─────────────────────────────────────────

    /**
     * The per-analysis list load, one at a time: it lists the cloud over the
     * network and is asked for from nine places (restore, delete, backup,
     * storage cleanup, finished work…). Overlapping loads used to land out of
     * order; now a burst runs at most one more load, after the current one.
     */
    private val analysesRefresh by lazy {
        ConflatedRefresh<Unit>(lifecycleScope, merge = { _, _ -> }) { loadAnalysesData() }
    }

    internal fun wireAnalysesDataSection() {
        // Storage cleanup re-enters here; the section does not exist on demo.
        if (!LicenseEntitlements.cloudBackupEnabled(this)) return
        analysesRefresh.request(Unit)
    }

    private suspend fun loadAnalysesData() {
        views.progressAnalysesData.isVisible = true
        views.tvAnalysesDataState.isVisible = false
        val records = withContext(Dispatchers.IO) { SessionStore.list(this@SettingsActivity) }
        // Full COMPLETED list (not listRestorable): stubs without local .dat
        // still need a Download action when the cloud copy exists.
        val result = CloudRestore.listCompleted(this@SettingsActivity)
        views.progressAnalysesData.isVisible = false

        cloudStateMessage(result)?.let {
            views.tvAnalysesDataState.isVisible = true
            views.tvAnalysesDataState.text = it
        }
        val cloud = (result as? CloudRestore.ListResult.Ready)?.sessions.orEmpty()
        val entries = withContext(Dispatchers.IO) {
            AnalysisEntries.merge(records, cloud).map { entry ->
                val id = entry.record?.id ?: return@map entry
                entry.copy(localBytes = SessionStore.sizeOf(this@SettingsActivity, id))
            }
        }
        if (entries.isEmpty()) {
            views.tvAnalysesDataState.isVisible = true
            views.tvAnalysesDataState.setText(R.string.analyses_data_empty)
        }
        analysesAdapter.submit(entries)
    }

    /**
     * Why the cloud half is missing, or null when it answered. Silence would be
     * wrong here: without this line a backed-up analysis looks phone-only, and
     * the user would read that as "my backup is gone".
     */
    private fun cloudStateMessage(result: CloudRestore.ListResult): String? = when (result) {
        is CloudRestore.ListResult.Ready, CloudRestore.ListResult.Empty -> null
        CloudRestore.ListResult.NeedSignIn -> getString(R.string.restore_need_sign_in)
        CloudRestore.ListResult.ApiOff -> getString(R.string.restore_api_off)
        is CloudRestore.ListResult.Failed -> getString(R.string.restore_load_error, result.reason)
    }

    /**
     * Open when the phone already has results; otherwise offer cloud restore when a
     * backup is attached to this management row.
     */
    private fun openOrDownloadAnalysis(entry: AnalysisEntry) {
        val record = entry.record
        if (record != null && entry.hasLocalData) {
            SessionOpenHelper.openOrExplain(this, record, hasLocalData = true)
            return
        }
        if (entry.cloud != null) {
            confirmCloudRestore(entry)
            return
        }
        if (record != null) {
            SessionOpenHelper.openOrExplain(this, record, hasLocalData = false)
        }
    }

    private fun confirmLocalDownload(entry: AnalysisEntry) {
        if (!entry.offersDownload()) return
        val key = entry.downloadKey()
        if (busy.isBusy(key)) {
            Feedback.toast(this, R.string.download_analysis_already)
            return
        }
        // Location picker is the confirmation — download starts only after the
        // user chooses where the Session.zip should be saved.
        pendingDownload = PendingBundleDownload(
            cloudSessionId = entry.cloud?.sessionId.orEmpty(),
            displayName = entry.name,
            localSessionId = entry.record?.id.orEmpty(),
        )
        createDownloadDocument.launch(CloudRestore.suggestedBundleFileName(entry.name))
    }

    /**
     * Enqueue a background download that writes into [destUri]. Called only
     * after SAF CreateDocument returns a destination.
     */
    private fun startBundleDownloadToUri(pending: PendingBundleDownload, destUri: Uri) {
        if (pending.cloudSessionId.isBlank()) {
            Feedback.toast(this, R.string.download_analysis_failed, long = true)
            return
        }
        val key = pending.cloudSessionId
        if (busy.isBusy(key)) {
            Feedback.toast(this, R.string.download_analysis_already)
            return
        }
        val granted = runCatching {
            contentResolver.takePersistableUriPermission(
                destUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Timber.e(it, "Could not persist write grant for destination") }
            .isSuccess
        if (!granted) {
            Feedback.toast(this, R.string.save_failed, long = true)
            return
        }
        // Marked before the enqueue, so the job is not lost if it finishes
        // before the observed list ever shows it running.
        markDownloading(key)
        val enqueued = runCatching {
            CloudRestore.enqueueBundleDownload(
                this,
                pending.cloudSessionId,
                pending.displayName,
                destUri = destUri.toString(),
                localSessionId = pending.localSessionId,
            )
        }.onFailure { Timber.e(it, "Could not enqueue bundle download") }
            .isSuccess
        if (!enqueued) {
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    destUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            unmarkDownloading(key)
            Feedback.toast(this, R.string.download_analysis_failed, long = true)
            return
        }
        transferBanner.upsert(
            TransferBannerController.Transfer(
                id = key,
                title = pending.displayName.ifBlank { getString(R.string.transfer_banner_download) },
                onCancel = { CloudRestore.cancelBundleDownload(this, pending.cloudSessionId) },
            ),
        )
        Feedback.toast(this, R.string.download_background_note)
    }

    private fun confirmCloudRestore(entry: AnalysisEntry) {
        if (!entry.offersRestore()) return
        val key = entry.downloadKey()
        if (busy.isBusy(key)) {
            markDownloading(key)
            Feedback.toast(this, R.string.download_analysis_already)
            return
        }
        Dialogs.confirm(
            this,
            R.string.download_analysis_title,
            R.string.download_analysis_body,
            R.string.restore_action,
        ) { restoreBackup(entry) }
    }

    private fun restoreBackup(entry: AnalysisEntry) {
        val cloud = entry.cloud ?: return
        val key = entry.downloadKey()
        if (busy.isBusy(key)) {
            markDownloading(key)
            Feedback.toast(this, R.string.download_analysis_already)
            return
        }
        // Prefer the existing phone row id so a freed stub / re-download fills
        // in-place rather than creating a second "restored-…" id.
        val targetLocalId = entry.record?.id ?: CloudRestore.targetLocalId(cloud)
        // Marked before the IO hop: the restore can run and finish inside it.
        markDownloading(key)
        lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) {
                RestoreStart.start(this@SettingsActivity, cloud.sessionId, targetLocalId, entry.name)
            }
            if (started == RestoreStart.Result.ALREADY_RUNNING) {
                Feedback.toast(this@SettingsActivity, R.string.download_analysis_already)
                return@launch
            }
            if (started != RestoreStart.Result.STARTED) {
                unmarkDownloading(key)
                Feedback.toast(this@SettingsActivity, R.string.restore_failed_generic, long = true)
                return@launch
            }
            transferBanner.upsert(
                TransferBannerController.Transfer(
                    id = key,
                    title = entry.name.ifBlank { getString(R.string.transfer_banner_restore) },
                    cancellable = false,
                ),
            )
            Feedback.toast(this@SettingsActivity, R.string.restore_background_note)
            wireAnalysesDataSection()
        }
    }

    private fun markDownloading(key: String) {
        busy.mark(key)
        publishBusy()
    }

    private fun unmarkDownloading(key: String) {
        busy.unmark(key)
        publishBusy()
    }

    private fun publishBusy() = analysesAdapter.setDownloadingKeys(busy.keys())

    /**
     * Restores ([com.indicvision.semper.data.DicRestoreWorker]) and
     * Save-to-Files downloads ([com.indicvision.semper.data.DicBundleDownloadWorker])
     * run in WorkManager. Watch both so
     * their rows go busy, the banner follows them even after leaving Analyses
     * data management, and each outcome is told once.
     */
    private fun observeTransfers() {
        // Best-effort: WorkManager is always initialized in production (its startup
        // provider runs before any Activity), but not in a unit-test harness that
        // skips that provider. Missing WorkManager must not crash onCreate — and if
        // it were truly absent, restore couldn't be enqueued in the first place.
        val workManager = runCatching { WorkManager.getInstance(this) }.getOrNull() ?: return
        TransferWorkObserver(TransferWork.Kind.RESTORE).observe(this, workManager) { update ->
            busy.onRestoreWork(update.jobs)
            publishBusy()
            update.jobs.forEach(::showRestoreInBanner)
            update.newlyFinished.forEach(::reportRestore)
        }
        TransferWorkObserver(TransferWork.Kind.BUNDLE_DOWNLOAD, presentedBundleDownloads)
            .observe(this, workManager) { update ->
                // Running, queued and blocked rows go busy through this list.
                busy.onDownloadWork(update.jobs)
                publishBusy()
                update.jobs.forEach(::showDownloadInBanner)
                update.newlyFinished.forEach(::reportDownload)
            }
    }

    /** A running restore's progress in the banner; a finished one leaves it. */
    private fun showRestoreInBanner(job: TransferWorkObserver.Job) {
        val key = job.cloudSessionId?.takeIf { it.isNotBlank() } ?: return
        val state = job.state
        when {
            state is TransferWork.State.Running -> {
                val percent = state.percent ?: 0
                if (!transferBanner.contains(key)) {
                    transferBanner.upsert(
                        TransferBannerController.Transfer(
                            id = key,
                            title = getString(R.string.transfer_banner_restore),
                            cancellable = false,
                            percent = percent,
                        ),
                    )
                } else {
                    transferBanner.updateProgress(key, percent)
                }
            }
            job.isFinished -> transferBanner.remove(key)
        }
    }

    /**
     * Without this a restore's outcome would be silent: the user taps Restore,
     * sees "continues in background", and is never told if it failed (backup
     * gone / not theirs / gave up). A success is silent on purpose (uploads
     * don't toast success either): the list reloads so the session appears.
     */
    private fun reportRestore(job: TransferWorkObserver.Job) {
        when (val state = job.state) {
            TransferWork.State.Succeeded -> wireAnalysesDataSection()
            // Once per failure across Home and Settings, not once per screen open.
            is TransferWork.State.Failed -> if (RestoreFailureLedger.claim(this, job.id)) {
                CrispToast.show(this, state.reason ?: getString(R.string.restore_failed_generic), long = true)
            }
            else -> Unit
        }
    }

    /** A queued or running download in the banner (progress once it runs); a finished one leaves it. */
    private fun showDownloadInBanner(job: TransferWorkObserver.Job) {
        val key = job.cloudSessionId?.takeIf { it.isNotBlank() } ?: return
        val state = job.state
        when {
            job.isFinished -> transferBanner.remove(key)
            !transferBanner.contains(key) -> transferBanner.upsert(
                TransferBannerController.Transfer(
                    id = key,
                    title = getString(R.string.transfer_banner_download),
                    percent = (state as? TransferWork.State.Running)?.percent ?: 0,
                    onCancel = { CloudRestore.cancelBundleDownload(this, key) },
                ),
            )
            state is TransferWork.State.Running -> transferBanner.updateProgress(key, state.percent ?: 0)
        }
    }

    /** A finished Save-to-Files write, told once per process ([presentedBundleDownloads]). */
    private fun reportDownload(job: TransferWorkObserver.Job) {
        when (val state = job.state) {
            TransferWork.State.Succeeded -> Feedback.toast(this, R.string.save_success, long = true)
            is TransferWork.State.Failed ->
                Feedback.toast(this, LicenseErrors.downloadMessage(this, state.reason), long = true)
            else -> Unit
        }
    }

    private fun deleteBackup(entry: AnalysisEntry, row: View) {
        val cloud = entry.cloud ?: return
        val record = entry.record
        if (record != null) {
            showDeleteBackupChoice(record, cloud, row)
        } else {
            confirmDeleteCloudBackup(cloud, entry.name, row)
        }
    }

    /** Which backup action a row offers, or null when none applies. */
    private fun backupLabel(entry: AnalysisEntry): Int? {
        val record = entry.record ?: return null
        return when (record.syncState) {
            SessionRecord.SyncState.FAILED, SessionRecord.SyncState.PENDING -> R.string.cloud_retry_backup
            SessionRecord.SyncState.LOCAL_ONLY ->
                if (DicSettings.saveToCloud(this)) R.string.cloud_backup_now else null
            // Backed up, but this run could not list the cloud: offer nothing
            // rather than a "back up" that would duplicate an existing copy.
            SessionRecord.SyncState.SYNCED -> null
        }
    }

    private fun startBackup(entry: AnalysisEntry) {
        val record = entry.record ?: return
        val label = backupLabel(entry) ?: return
        if (!IndicApi.get(this).enabled) {
            Feedback.toast(this, R.string.cloud_backup_no_backend, long = true)
            return
        }
        // Same ordering as Home's: the PENDING stamp before the worker, so a
        // fast upload cannot have its SYNCED stamp overwritten by this one.
        lifecycleScope.launch {
            SessionStore.setSyncStateAsync(this@SettingsActivity, record.id, SessionRecord.SyncState.PENDING)
            CloudSync.enqueueUpload(this@SettingsActivity, record.id)
            Feedback.toast(this@SettingsActivity, label)
            wireAnalysesDataSection()
        }
    }

    private fun stateLine(entry: AnalysisEntry): String = when (entry.location) {
        AnalysisLocation.CLOUD_ONLY ->
            getString(R.string.analysis_state_cloud_only_fmt, ByteSize.format(entry.cloud?.totalBytes ?: 0L))
        AnalysisLocation.PHONE_AND_CLOUD ->
            getString(R.string.analysis_state_phone_and_cloud_fmt, ByteSize.format(entry.cloud?.totalBytes ?: 0L))
        AnalysisLocation.PHONE_ONLY ->
            if (entry.localBytes > 0) {
                getString(R.string.analysis_state_on_phone_fmt, ByteSize.format(entry.localBytes))
            } else {
                getString(R.string.analysis_state_phone_only)
            }
        // The cloud could not confirm this one: keep its badge rather than
        // claiming the backup is gone.
        AnalysisLocation.PHONE_SYNC_STATE ->
            entry.record?.let { syncLabel(it.syncState) }.orEmpty()
    }

    // ── Deletion, with an undo window ────────────────────────────────────

    /**
     * Deleting a cloud backup is irreversible — there is no trash on the
     * backend — so the confirm spells that out before anything is scheduled.
     */
    private fun confirmDeleteCloudBackup(session: CloudSessionDto, name: String, row: View) {
        Dialogs.confirm(
            this,
            getText(R.string.cloud_delete_forever_title),
            getString(R.string.cloud_delete_forever_body, name),
            R.string.cloud_delete_forever_confirm,
        ) { scheduleDelete(row, session.localSessionId, session.sessionId, SessionDeletes.Mode.CLOUD) }
    }

    private fun showDeleteBackupChoice(record: SessionRecord, cloud: CloudSessionDto, row: View) {
        DeleteChoiceDialog.show(
            activity = this,
            title = getString(R.string.delete_confirm_title),
            message = getString(R.string.delete_confirm_body_cloud),
            choices = listOf(
                DeleteChoiceDialog.Choice(getString(R.string.delete_choice_phone)) {
                    lifecycleScope.launch {
                        CloudSync.eraseLocalOnly(this@SettingsActivity, record.id)
                        toast(getString(R.string.delete_device_only_done))
                        wireAnalysesDataSection()
                    }
                },
                DeleteChoiceDialog.Choice(getString(R.string.delete_choice_cloud)) {
                    scheduleDelete(row, record.id, cloud.sessionId, SessionDeletes.Mode.CLOUD)
                },
                DeleteChoiceDialog.Choice(getString(R.string.delete_choice_everywhere)) {
                    scheduleDelete(row, record.id, cloud.sessionId, SessionDeletes.Mode.EVERYWHERE)
                },
            ),
        )
    }

    /**
     * The safety net: the row goes at once, but the deletion waits in
     * [SessionDeletes] for the undo window and only then reaches the backend,
     * so a mis-tapped bin followed by a reflexive confirm is still
     * recoverable. Leaving the page (or the app) does not abandon it: the user
     * confirmed, and the worker retries if the network is down.
     */
    private fun scheduleDelete(row: View, localSessionId: String, cloudSessionId: String, mode: SessionDeletes.Mode) {
        analysesAdapter.removeAt(views.analysesDataList.getChildAdapterPosition(row))
        val workId = SessionDeletes.enqueue(this, listOf(SessionDeletes.Item(localSessionId, cloudSessionId, mode)))
        deleteFeedback.queued(workId, 1)
    }

    // Host helpers used by extracted sections.

    internal fun launchReauth() {
        reauthLauncher.launch(AuthActivity.reauthIntent(this))
    }

    internal fun toast(message: String) =
        CrispToast.show(this, message, long = true)

    private fun wireFooter() {
        views.btnAbout.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(
                    getString(
                        R.string.about_message,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                )
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.legal_privacy) { _, _ ->
                    ExternalLinks.open(this, getString(R.string.legal_privacy_url))
                }
                .setNegativeButton(R.string.legal_terms) { _, _ ->
                    ExternalLinks.open(this, getString(R.string.legal_terms_url))
                }
                .show()
        }
        views.btnSignOut.setOnClickListener {
            // Outside this screen, so a rotation cannot half sign out; the
            // observer below routes once it is done.
            val app = applicationContext
            SignOutRun.confirm(this) { AuthRepository(app).signOut() }
        }
        SignOutRun.observe(this) { AuthRoute.toSignIn(this) }
    }

    // ── Formatting ───────────────────────────────────────────────────────

    private fun syncLabel(state: SessionRecord.SyncState): String = when (state) {
        SessionRecord.SyncState.SYNCED -> getString(R.string.badge_synced)
        SessionRecord.SyncState.PENDING -> getString(R.string.badge_pending)
        SessionRecord.SyncState.LOCAL_ONLY -> getString(R.string.badge_local)
        SessionRecord.SyncState.FAILED -> getString(R.string.badge_not_backed_up)
    }

    private data class PendingBundleDownload(
        val cloudSessionId: String,
        val displayName: String,
        val localSessionId: String,
    ) {
        fun writeTo(out: Bundle) {
            out.putString(STATE_DL_CLOUD, cloudSessionId)
            out.putString(STATE_DL_NAME, displayName)
            out.putString(STATE_DL_LOCAL, localSessionId)
        }

        companion object {
            fun fromBundle(state: Bundle?): PendingBundleDownload? {
                val cloud = state?.getString(STATE_DL_CLOUD)?.takeIf { it.isNotBlank() } ?: return null
                return PendingBundleDownload(
                    cloudSessionId = cloud,
                    displayName = state.getString(STATE_DL_NAME).orEmpty(),
                    localSessionId = state.getString(STATE_DL_LOCAL).orEmpty(),
                )
            }
        }
    }

    companion object {
        private const val STATE_DL_CLOUD = "pending_dl_cloud"
        private const val STATE_DL_NAME = "pending_dl_name"
        private const val STATE_DL_LOCAL = "pending_dl_local"

        /** Work ids whose Save-to-Files outcome was already shown (process-wide). */
        private val presentedBundleDownloads: MutableSet<java.util.UUID> =
            java.util.Collections.synchronizedSet(mutableSetOf())

        const val CHEVRON_EXPANDED_DEG = 180f
        const val ZIP_MIME = "application/zip"
        const val JSON_MIME = "application/json"
        const val PERCENT_MAX = 100
    }
}
