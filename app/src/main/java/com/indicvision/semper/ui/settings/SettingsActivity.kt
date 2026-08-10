// The settings page wires every section in one screen — account, cloud,
// per-analysis data, export/erasure and defaults — kept together for locality.
// The many small wireX/helpers push it past the LongMethod / TooManyFunctions /
// LargeClass thresholds; splitting a screen whose handlers share this Activity's
// launchers and views would trade that locality for cross-class state plumbing,
// so those rules are suppressed for this file rather than worked around.

@file:Suppress("TooManyFunctions", "LargeClass")

package com.indicvision.semper.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.Diagnostics
import com.indicvision.semper.R
import com.indicvision.semper.data.AuthRepository
import com.indicvision.semper.data.BackupDeleteWorker
import com.indicvision.semper.data.CacheJanitor
import com.indicvision.semper.data.CloudRestore
import com.indicvision.semper.data.CloudSync
import com.indicvision.semper.data.DevAuth
import com.indicvision.semper.data.DeviceKeyManager
import com.indicvision.semper.data.DicRestoreWorker
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.SessionEverythingExporter
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.StorageBudget
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.ui.admin.AdminActivity
import com.indicvision.semper.ui.auth.AuthActivity
import com.indicvision.semper.ui.common.AuthRoute
import com.indicvision.semper.ui.common.DeterminateProgressDialog
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.home.SessionOpenHelper
import com.indicvision.semper.ui.viewer.SendToSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Settings: account, cloud preferences, per-analysis data management, data
 * export/erasure and analysis defaults. A page rather than a sheet — Home
 * refreshes in `onResume`, so anything changed here is reflected on return.
 */
class SettingsActivity : AppCompatActivity() {

    /**
     * Registered up front, as activity-result launchers must be: the delete flow
     * hands off to the sign-in screen and only proceeds if it answers OK.
     */
    private val reauthLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) deleteAccount()
    }

    private lateinit var analysesList: RecyclerView
    private lateinit var analysesAdapter: AnalysisDataAdapter
    private lateinit var analysesProgress: ProgressBar
    private lateinit var analysesState: TextView

    /** Restore WorkInfo ids already surfaced, so one outcome isn't shown twice. */
    private val shownRestoreOutcomes = mutableSetOf<java.util.UUID>()

    /** In-flight Save-to-Files downloads keyed by [AnalysisEntry.downloadKey]. */
    private val filesDownloadJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /** Restore / files-download keys currently busy — disables the Download icon. */
    private val downloadingKeys = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // Edge-to-edge: without this the status bar swallows taps on the back arrow.
        Insets.padTop(findViewById(R.id.settingsTopBar))
        Insets.padBottom(findViewById(R.id.settingsScroll))

        findViewById<ImageButton>(R.id.btnSettingsBack).setOnClickListener { finish() }

        analysesAdapter = AnalysisDataAdapter(
            stateLine = ::stateLine,
            backupLabel = ::backupLabel,
            onOpen = ::openOrDownloadAnalysis,
            onBackup = ::startBackup,
            onLocalDownload = ::confirmLocalDownload,
            onCloudRestore = ::confirmCloudRestore,
            onDelete = ::deleteBackup,
        )
        analysesList = findViewById<RecyclerView>(R.id.analysesDataList).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = analysesAdapter
        }
        analysesProgress = findViewById(R.id.progressAnalysesData)
        analysesState = findViewById(R.id.tvAnalysesDataState)

        wireCollapsible(R.id.headerAccount, R.id.bodyAccount, R.id.ivAccountChevron)
        wireCollapsible(R.id.headerCloud, R.id.bodyCloud, R.id.ivCloudChevron)
        wireCollapsible(R.id.headerAnalysesData, R.id.bodyAnalysesData, R.id.ivAnalysesDataChevron)
        wireCollapsible(R.id.headerStorage, R.id.bodyStorage, R.id.ivStorageChevron)
        wireCollapsible(R.id.headerYourData, R.id.bodyYourData, R.id.ivYourDataChevron)
        wireCollapsible(R.id.headerAnalysisPrefs, R.id.bodyAnalysisPrefs, R.id.ivAnalysisPrefsChevron)
        wireCollapsible(R.id.headerHelpSupport, R.id.bodyHelpSupport, R.id.ivHelpSupportChevron)

        observeRestoreOutcomes()

        wireAccountSection()
        wireCloudSection()
        wireAnalysesDataSection()
        wireStorageSection()
        wireYourDataSection()
        wirePreferencesSection()
        wireHelpSupportSection()
        wireFooter()
    }

    private fun wireCollapsible(headerId: Int, bodyId: Int, chevronId: Int, startExpanded: Boolean = false) {
        val header = findViewById<View>(headerId)
        val body = findViewById<View>(bodyId)
        val chevron = findViewById<ImageView>(chevronId)
        fun apply(expanded: Boolean) {
            body.isVisible = expanded
            chevron.rotation = if (expanded) CHEVRON_EXPANDED_DEG else 0f
        }
        apply(startExpanded)
        header.setOnClickListener { apply(body.visibility != View.VISIBLE) }
    }

    // ── Account ──────────────────────────────────────────────────────────

    private fun wireAccountSection() {
        findViewById<TextView>(R.id.tvAccountEmail).text = TokenStore.cachedEmail(this).orEmpty()
        val deviceId = runCatching { DeviceKeyManager(this).getDeviceId() }.getOrDefault("")
        findViewById<TextView>(R.id.tvAccountDevice).text =
            getString(R.string.account_device_id_fmt, deviceId)

        findViewById<View>(R.id.btnAdmin).apply {
            isVisible = TokenStore.isAdmin(this@SettingsActivity)
            setOnClickListener { startActivity(Intent(this@SettingsActivity, AdminActivity::class.java)) }
        }
    }

    // ── Cloud backup ─────────────────────────────────────────────────────

    private fun wireCloudSection() {
        val switchSave = findViewById<SwitchMaterial>(R.id.switchSaveCloud)
        val switchWifi = findViewById<SwitchMaterial>(R.id.switchWifiOnly)
        val sub = findViewById<TextView>(R.id.tvSaveCloudSub)
        val status = findViewById<TextView>(R.id.tvCloudSyncStatus)

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
            val pending = withContext(Dispatchers.IO) {
                SessionStore.list(this@SettingsActivity).count { it.syncState == SessionRecord.SyncState.PENDING }
            }
            status.setText(if (pending > 0) R.string.badge_pending else R.string.sync_status_up_to_date)
        }
    }

    private fun maybeOfferBackfill() {
        lifecycleScope.launch {
            val localOnly = withContext(Dispatchers.IO) {
                SessionStore.list(this@SettingsActivity)
                    .filter { it.syncState == SessionRecord.SyncState.LOCAL_ONLY }
            }
            if (localOnly.isEmpty()) return@launch
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.cloud_backfill_title)
                .setMessage(resources.getQuantityString(R.plurals.cloud_backfill_body, localOnly.size, localOnly.size))
                .setPositiveButton(R.string.cloud_backfill_confirm) { _, _ ->
                    localOnly.forEach { CloudSync.enqueueUpload(this@SettingsActivity, it.id) }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    // ── Analyses data management ─────────────────────────────────────────

    private fun wireAnalysesDataSection() {
        analysesProgress.isVisible = true
        analysesState.isVisible = false

        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) { SessionStore.list(this@SettingsActivity) }
            // Full COMPLETED list (not listRestorable): stubs without local .dat
            // still need a Download action when the cloud copy exists.
            val result = CloudRestore.listCompleted(this@SettingsActivity)
            analysesProgress.isVisible = false

            cloudStateMessage(result)?.let {
                analysesState.isVisible = true
                analysesState.text = it
            }
            val cloud = (result as? CloudRestore.ListResult.Ready)?.sessions.orEmpty()
            val entries = withContext(Dispatchers.IO) {
                AnalysisEntries.merge(records, cloud).map { entry ->
                    val id = entry.record?.id ?: return@map entry
                    entry.copy(localBytes = SessionStore.sizeOf(this@SettingsActivity, id))
                }
            }
            if (entries.isEmpty()) {
                analysesState.isVisible = true
                analysesState.setText(R.string.analyses_data_empty)
            }
            analysesAdapter.submit(entries)
        }
    }

    // ── Storage ──────────────────────────────────────────────────────────

    private fun wireStorageSection() {
        findViewById<View>(R.id.btnStorageFreeUp).setOnClickListener { confirmFreeUpSpace() }
        findViewById<View>(R.id.btnStorageClearCache).setOnClickListener { clearTemporaryFiles() }
        findViewById<ImageButton>(R.id.btnAutoFreeInfo).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.storage_auto_free)
                .setMessage(R.string.storage_auto_free_info)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        val valueLabel = findViewById<TextView>(R.id.tvAutoFreeValue)
        findViewById<Slider>(R.id.sliderAutoFree).apply {
            valueTo = DicSettings.MAX_AUTO_FREE_GB.toFloat()
            value = DicSettings.autoFreeBudgetGb(this@SettingsActivity)
                .toFloat().coerceIn(valueFrom, valueTo)
            valueLabel.text = autoFreeText(value.toInt())
            addOnChangeListener { _, v, fromUser ->
                valueLabel.text = autoFreeText(v.toInt())
                if (!fromUser) return@addOnChangeListener
                DicSettings.setAutoFreeBudgetGb(this@SettingsActivity, v.toInt())
                // Applying on release rather than on every tick: dragging past a
                // low value would otherwise start dropping sessions mid-gesture.
            }
            addOnSliderTouchListener(
                object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) = Unit
                    override fun onStopTrackingTouch(slider: Slider) = applyStorageBudget()
                },
            )
        }

        refreshStorageTotals()
    }

    private fun autoFreeText(gb: Int): String =
        if (gb <= DicSettings.AUTO_FREE_OFF) {
            getString(R.string.storage_auto_free_off)
        } else {
            getString(R.string.storage_auto_free_on_fmt, gb)
        }

    /** Measures off the main thread — a full sessions tree is a lot of stat calls. */
    private fun refreshStorageTotals() {
        lifecycleScope.launch {
            val sizes = withContext(Dispatchers.IO) {
                Triple(
                    SessionStore.totalSize(this@SettingsActivity),
                    // Show what Clear will free — not raw cacheDir size (which
                    // includes a live import the button must not delete).
                    CacheJanitor.clearableUserBytes(this@SettingsActivity),
                    StorageBudget.reclaimableBytes(this@SettingsActivity),
                )
            }
            val (analyses, cache, reclaimable) = sizes
            findViewById<TextView>(R.id.tvStorageAnalysesSize).text = humanSize(analyses)
            findViewById<TextView>(R.id.tvStorageCacheSize).text = humanSize(cache)
            findViewById<View>(R.id.btnStorageClearCache).isEnabled = cache > 0

            val freeUpSub = findViewById<TextView>(R.id.tvStorageFreeUpSub)
            findViewById<View>(R.id.btnStorageFreeUp).isEnabled = reclaimable > 0
            freeUpSub.text = if (reclaimable > 0) {
                getString(R.string.storage_free_up_sub_fmt, humanSize(reclaimable))
            } else {
                getString(R.string.storage_free_up_none)
            }
        }
    }

    private fun confirmFreeUpSpace() {
        lifecycleScope.launch {
            val reclaimable = withContext(Dispatchers.IO) {
                StorageBudget.reclaimableBytes(this@SettingsActivity)
            }
            if (reclaimable <= 0) {
                toast(getString(R.string.storage_freed_none))
                return@launch
            }
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.storage_free_up_title)
                .setMessage(getString(R.string.storage_free_up_body, humanSize(reclaimable)))
                .setPositiveButton(R.string.storage_free_up_confirm) { _, _ -> freeUpSpace() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun freeUpSpace() {
        lifecycleScope.launch {
            val outcome = StorageBudget.freeAllBackedUpAsync(this@SettingsActivity)
            if (outcome.didAnything) {
                toast(getString(R.string.storage_freed_fmt, humanSize(outcome.freedBytes), outcome.sessionsDropped))
            } else {
                toast(getString(R.string.storage_freed_none))
            }
            refreshStorageTotals()
            wireAnalysesDataSection()
        }
    }

    private fun clearTemporaryFiles() {
        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) {
                CacheJanitor.sweepUserRequested(this@SettingsActivity)
            }
            if (freed > 0) {
                toast(getString(R.string.storage_cache_cleared_fmt, humanSize(freed)))
            } else {
                toast(getString(R.string.storage_cache_cleared_none))
            }
            refreshStorageTotals()
        }
    }

    private fun applyStorageBudget() {
        lifecycleScope.launch {
            val outcome = StorageBudget.enforceAsync(this@SettingsActivity)
            if (outcome.didAnything) {
                toast(getString(R.string.storage_freed_fmt, humanSize(outcome.freedBytes), outcome.sessionsDropped))
                wireAnalysesDataSection()
            }
            refreshStorageTotals()
        }
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
        if (record?.hasLocalData() == true) {
            SessionOpenHelper.openOrExplain(this, record)
            return
        }
        if (entry.cloud != null) {
            confirmCloudRestore(entry)
            return
        }
        if (record != null) {
            SessionOpenHelper.openOrExplain(this, record)
        }
    }

    private fun confirmLocalDownload(entry: AnalysisEntry) {
        if (!entry.offersDownload()) return
        val key = entry.downloadKey()
        if (key in downloadingKeys || filesDownloadJobs[key]?.isActive == true) {
            Toast.makeText(this, R.string.download_analysis_already, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_analysis_save_title)
            .setMessage(R.string.download_analysis_save_body)
            .setPositiveButton(R.string.download_local_action) { _, _ ->
                saveBackupCopyToFiles(entry)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmCloudRestore(entry: AnalysisEntry) {
        if (!entry.offersRestore()) return
        val key = entry.downloadKey()
        if (key in downloadingKeys || filesDownloadJobs[key]?.isActive == true || isRestoreWorkRunning(key)) {
            markDownloading(key, true)
            Toast.makeText(this, R.string.download_analysis_already, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_analysis_title)
            .setMessage(R.string.download_analysis_body)
            .setPositiveButton(R.string.restore_action) { _, _ ->
                restoreBackup(entry)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Save Session.zip via Save / Share. Falls back to packing the on-device
     * session if the cloud zip is unavailable. Never unpacks into the session dir.
     */
    private fun saveBackupCopyToFiles(entry: AnalysisEntry) {
        val cloud = entry.cloud ?: return
        val key = entry.downloadKey()
        if (key in downloadingKeys || filesDownloadJobs[key]?.isActive == true) {
            Toast.makeText(this, R.string.download_analysis_already, Toast.LENGTH_SHORT).show()
            return
        }
        var job: kotlinx.coroutines.Job? = null
        val progress = DeterminateProgressDialog(
            this,
            getString(R.string.download_analysis_working),
            onCancel = { job?.cancel() },
        )
        progress.show()
        markDownloading(key, true)
        job = lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    runCatching {
                        CloudRestore.downloadBundleZip(
                            this@SettingsActivity,
                            cloud.sessionId,
                            entry.name,
                        ) { done, total ->
                            val pct = if (total > 0L) {
                                ((done * PERCENT_MAX) / total).toInt().coerceIn(0, PERCENT_MAX)
                            } else {
                                0
                            }
                            progress.update(percent = pct)
                        }
                    }.onFailure { Timber.w(it, "Cloud Session.zip download for save failed") }
                        .getOrNull()
                        ?: entry.record?.takeIf { it.hasLocalData() }?.let { record ->
                            SessionEverythingExporter.exportSessionZip(this@SettingsActivity, record)
                        }
                }
                progress.dismiss()
                val ready = file?.takeIf { it.exists() && it.length() > 0L }
                if (ready == null) {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.download_analysis_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                SendToSheet.show(this@SettingsActivity, ready, ZIP_MIME)
            } catch (e: kotlinx.coroutines.CancellationException) {
                progress.dismiss()
                throw e
            } finally {
                filesDownloadJobs.remove(key)
                markDownloading(key, false)
            }
        }
        filesDownloadJobs[key] = job
    }

    private fun restoreBackup(entry: AnalysisEntry) {
        val cloud = entry.cloud ?: return
        val key = entry.downloadKey()
        if (isRestoreWorkRunning(cloud.sessionId) || key in downloadingKeys) {
            markDownloading(key, true)
            Toast.makeText(this, R.string.download_analysis_already, Toast.LENGTH_SHORT).show()
            return
        }
        // Prefer the existing phone row id so a freed stub / re-download fills
        // in-place rather than creating a second "restored-…" id.
        val targetLocalId = entry.record?.id ?: CloudRestore.targetLocalId(cloud)
        lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) {
                enqueueRestoreWithStub(entry, cloud, targetLocalId)
            }
            if (!started) {
                Toast.makeText(this@SettingsActivity, R.string.restore_failed_generic, Toast.LENGTH_LONG).show()
                return@launch
            }
            markDownloading(key, true)
            Toast.makeText(this@SettingsActivity, R.string.restore_background_note, Toast.LENGTH_SHORT).show()
            wireAnalysesDataSection()
        }
    }

    private fun isRestoreWorkRunning(cloudSessionId: String): Boolean {
        if (cloudSessionId.isBlank()) return false
        val wm = runCatching { WorkManager.getInstance(this) }.getOrNull()
        return wm != null && runCatching {
            wm.getWorkInfosForUniqueWork(CloudRestore.workName(cloudSessionId)).get()
                .any { !it.state.isFinished }
        }.getOrDefault(false)
    }

    private fun markDownloading(key: String, busy: Boolean) {
        if (busy) downloadingKeys.add(key) else downloadingKeys.remove(key)
        analysesAdapter.setDownloadingKeys(downloadingKeys.toSet())
    }

    /** Drop finished restore keys; keep active Save-to-Files jobs. */
    private fun syncDownloadingKeys() {
        val next = mutableSetOf<String>()
        filesDownloadJobs.filterValues { it.isActive }.keys.forEach { next += it }
        downloadingKeys.filter { isRestoreWorkRunning(it) }.forEach { next += it }
        downloadingKeys.clear()
        downloadingKeys.addAll(next)
        analysesAdapter.setDownloadingKeys(downloadingKeys.toSet())
    }

    @Suppress("ReturnCount")
    private fun enqueueRestoreWithStub(
        entry: AnalysisEntry,
        cloud: CloudSessionDto,
        targetLocalId: String,
    ): Boolean {
        val existing = SessionStore.get(this, targetLocalId)
        // Restore is only offered when local frames are missing.
        if (existing?.hasLocalData() == true) return false
        val stub = restoreStub(entry, cloud, targetLocalId, existing)
        if (!SessionStore.upsert(this, stub, allowOverLimit = true)) return false
        return try {
            CloudRestore.enqueueRestore(this, cloud.sessionId, targetLocalId)
            true
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.e(e, "Could not enqueue restore %s", cloud.sessionId)
            if (existing == null) {
                SessionStore.delete(this, targetLocalId)
            } else {
                SessionStore.upsert(this, existing, allowOverLimit = true)
            }
            false
        }
    }

    private fun restoreStub(
        entry: AnalysisEntry,
        cloud: CloudSessionDto,
        targetLocalId: String,
        existing: SessionRecord?,
    ): SessionRecord {
        val now = System.currentTimeMillis()
        return existing?.copy(
            name = existing.name.ifBlank { entry.name },
            updatedAt = now,
            cloudSessionId = cloud.sessionId,
            syncState = SessionRecord.SyncState.SYNCED,
        ) ?: SessionRecord(
            id = targetLocalId,
            name = entry.name,
            createdAt = now,
            updatedAt = now,
            frameCount = 0,
            subset = 0,
            step = 0,
            strainWindow = 0,
            imgW = 0,
            imgH = 0,
            roiX = 0,
            roiY = 0,
            roiW = 0,
            roiH = 0,
            refPath = "",
            refName = "",
            sessionDir = SessionStore.dirFor(this, targetLocalId).absolutePath,
            cloudSessionId = cloud.sessionId,
            syncState = SessionRecord.SyncState.SYNCED,
        )
    }

    /**
     * A restore runs in [com.indicvision.semper.data.DicRestoreWorker], so without
     * this its outcome would be silent — the user taps Restore, sees "continues in
     * background", and is never told if it failed (backup gone / not theirs / gave
     * up). Watch the "restore" work tag and surface each terminal outcome once:
     * failure with its reason, success with a confirmation + a refreshed list.
     */
    private fun observeRestoreOutcomes() {
        // Best-effort: WorkManager is always initialized in production (its startup
        // provider runs before any Activity), but not in a unit-test harness that
        // skips that provider. Missing WorkManager must not crash onCreate — and if
        // it were truly absent, restore couldn't be enqueued in the first place.
        val workManager = runCatching { WorkManager.getInstance(this) }.getOrNull() ?: return
        workManager
            .getWorkInfosByTagLiveData("restore")
            .observe(this) { infos ->
                infos.orEmpty().forEach { info ->
                    when (info.state) {
                        WorkInfo.State.FAILED -> {
                            if (shownRestoreOutcomes.add(info.id)) {
                                syncDownloadingKeys()
                                val reason = info.outputData.getString(DicRestoreWorker.KEY_ERROR)
                                    ?: getString(R.string.restore_failed_generic)
                                Snackbar.make(
                                    findViewById(android.R.id.content),
                                    getString(R.string.restore_failed_fmt, reason),
                                    Snackbar.LENGTH_LONG,
                                ).show()
                            }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            // Silent on purpose (uploads don't toast success either): just
                            // refresh so the restored session appears. Deduped so a retained
                            // old success doesn't reload on every screen open.
                            if (shownRestoreOutcomes.add(info.id)) {
                                syncDownloadingKeys()
                                wireAnalysesDataSection()
                            }
                        }
                        WorkInfo.State.CANCELLED -> syncDownloadingKeys()
                        else -> Unit
                    }
                }
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
        SessionStore.setSyncState(this, record.id, SessionRecord.SyncState.PENDING)
        CloudSync.enqueueUpload(this, record.id)
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        wireAnalysesDataSection()
    }

    private fun stateLine(entry: AnalysisEntry): String = when (entry.location) {
        AnalysisLocation.CLOUD_ONLY ->
            getString(R.string.analysis_state_cloud_only_fmt, humanSize(entry.cloud?.totalBytes ?: 0L))
        AnalysisLocation.PHONE_AND_CLOUD ->
            getString(R.string.analysis_state_phone_and_cloud_fmt, humanSize(entry.cloud?.totalBytes ?: 0L))
        AnalysisLocation.PHONE_ONLY ->
            if (entry.localBytes > 0) {
                getString(R.string.analysis_state_on_phone_fmt, humanSize(entry.localBytes))
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_delete_forever_title)
            .setMessage(getString(R.string.cloud_delete_forever_body, name))
            .setPositiveButton(R.string.cloud_delete_forever_confirm) { _, _ ->
                scheduleDelete(row, session.sessionId, session.localSessionId, alsoLocal = false)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDeleteBackupChoice(record: SessionRecord, cloud: CloudSessionDto, row: View) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_delete_backup_title)
            .setMessage(R.string.cloud_delete_backup_body)
            // Addressed by backend id: this row only exists because the cloud
            // listed that backup, so no lookup is needed.
            .setPositiveButton(R.string.cloud_delete_backup_only) { _, _ ->
                scheduleDelete(row, cloud.sessionId, record.id, alsoLocal = false)
            }
            .setNeutralButton(R.string.cloud_delete_backup_and_local) { _, _ ->
                scheduleDelete(row, cloud.sessionId, record.id, alsoLocal = true)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * The safety net: the row goes at once, but the deletion sits in
     * [BackupDeleteWorker] for the undo window and only then reaches the
     * backend — so a mis-tapped bin followed by a reflexive confirm is still
     * recoverable. Leaving the page (or the app) does not abandon it: the user
     * confirmed, and the worker retries if the network is down.
     */
    private fun scheduleDelete(row: View, cloudSessionId: String, localSessionId: String, alsoLocal: Boolean) {
        analysesAdapter.removeAt(analysesList.getChildAdapterPosition(row))
        BackupDeleteWorker.enqueue(this, cloudSessionId, localSessionId, alsoLocal)
        Snackbar.make(findViewById(R.id.settingsRoot), R.string.cloud_delete_pending, UNDO_WINDOW_MS)
            .setAction(R.string.action_undo) {
                BackupDeleteWorker.cancel(this, cloudSessionId)
                wireAnalysesDataSection()
            }
            .show()
    }

    // ── Your data / preferences / footer ─────────────────────────────────

    private fun wireYourDataSection() {
        findViewById<View>(R.id.btnExportData).setOnClickListener { exportMyData() }
        findViewById<View>(R.id.btnExportCloudData).setOnClickListener { exportCloudAccountData() }
        findViewById<View>(R.id.btnDeleteAccount).setOnClickListener { confirmDeleteAccount() }

        val switchDiagnostics = findViewById<SwitchMaterial>(R.id.switchDiagnostics)
        switchDiagnostics.isChecked = DicSettings.diagnosticsEnabled(this)
        switchDiagnostics.setOnCheckedChangeListener { _, checked ->
            // Applies immediately in both directions: turning this off also
            // deletes any crash report still queued on disk.
            Diagnostics.setEnabled(this, checked)
        }
    }

    /**
     * Download what the *cloud* holds about this account (GDPR Art. 20).
     *
     * Separate from [exportMyData], which bundles the sessions on this device.
     * The policy has always promised this; until now it existed only as an API
     * endpoint with no way for a user to reach it.
     */
    private fun exportCloudAccountData() {
        val api = IndicApi.get(this)
        if (!api.enabled) {
            Toast.makeText(this, R.string.export_cloud_data_offline, Toast.LENGTH_LONG).show()
            return
        }
        var job: kotlinx.coroutines.Job? = null
        val progress = DeterminateProgressDialog(
            this,
            getString(R.string.export_cloud_data_working),
            onCancel = { job?.cancel() },
        )
        progress.show()
        job = lifecycleScope.launch {
            try {
                val dest = java.io.File(cacheDir, "semper-account-export.json")
                val ok = runCatching {
                    val idToken = TokenProvider.usableIdToken() ?: error("not signed in")
                    api.exportAccount(idToken, dest)
                }.onFailure { Timber.w(it, "Cloud account export failed") }.isSuccess
                progress.dismiss()
                if (!ok || !dest.exists() || dest.length() == 0L) {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.export_cloud_data_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                SendToSheet.show(this@SettingsActivity, dest, JSON_MIME)
            } catch (e: kotlinx.coroutines.CancellationException) {
                progress.dismiss()
                throw e
            }
        }
    }

    private fun exportMyData() {
        var job: kotlinx.coroutines.Job? = null
        val progress = DeterminateProgressDialog(
            this,
            getString(R.string.export_data_working),
            onCancel = { job?.cancel() },
        )
        progress.show()
        job = lifecycleScope.launch {
            try {
                val export = SessionEverythingExporter.exportMasterZip(this@SettingsActivity) { done, total ->
                    progress.update(
                        percent = if (total > 0) done * 100 / total else 0,
                        text = getString(R.string.export_progress_fmt, done, total),
                    )
                }
                progress.dismiss()
                // Safety: only share a file that actually exists and has content.
                val file = export?.file?.takeIf { it.exists() && it.length() > 0L }
                if (file == null) {
                    Toast.makeText(this@SettingsActivity, R.string.export_data_failed, Toast.LENGTH_LONG).show()
                    return@launch
                }
                // Save to Files (folder icon) + Share, via our own sheet — the system
                // chooser can't show a custom icon on its initial intents (Android 12+).
                SendToSheet.show(this@SettingsActivity, file, ZIP_MIME)
            } catch (e: kotlinx.coroutines.CancellationException) {
                progress.dismiss()
                throw e
            }
        }
    }

    private fun confirmDeleteAccount() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_account_title)
            .setMessage(R.string.delete_account_body)
            .setPositiveButton(R.string.delete_account_confirm) { _, _ -> verifyThenDeleteAccount() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Erasing an identity is the one action a stolen unlocked phone must not be
     * able to perform on an old session, so we prove who is holding it first.
     * Firebase also refuses to delete a user whose sign-in has gone stale, which
     * is what used to leave the identity behind after the data was gone.
     *
     * The proof happens on the sign-in screen itself — it already knows every
     * way into this account, including the emailed link, which a password box
     * here never could.
     */
    private fun verifyThenDeleteAccount() {
        if (DevAuth.active) {
            // The emulator bypass never signed in, so there is no identity to
            // prove — and no real account to protect either.
            deleteAccount()
            return
        }
        reauthLauncher.launch(AuthActivity.reauthIntent(this))
    }

    private fun deleteAccount() {
        // Erasure walks the cloud copy before it touches anything local, so it
        // can take a few seconds on a full account. Without this the screen just
        // sits there and the only feedback is the app appearing to have hung.
        val progress = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_account_working)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val outcome = CloudSync.deleteAccount(this@SettingsActivity)
            progress.dismiss()
            when (outcome) {
                CloudSync.AccountDeletion.DELETED -> {
                    toast(getString(R.string.delete_account_done))
                    AuthRoute.toSignIn(this@SettingsActivity)
                }
                // Data is gone either way, so the session must not continue.
                CloudSync.AccountDeletion.IDENTITY_KEPT -> {
                    toast(getString(R.string.delete_account_identity_kept))
                    AuthRoute.toSignIn(this@SettingsActivity)
                }
                CloudSync.AccountDeletion.CLOUD_UNREACHABLE ->
                    toast(getString(R.string.delete_account_failed))
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun wirePreferencesSection() {
        val valueLabel = findViewById<TextView>(R.id.tvMaxFramesValue)
        val remoteMaxFrames = AppRemoteConfig.maxFrames(this)
        val ceiling = DicSettings.frameCeiling(remoteMaxFrames).toFloat()
        findViewById<Slider>(R.id.sliderMaxFrames).apply {
            valueTo = ceiling
            value = DicSettings.maxFrames(this@SettingsActivity, remoteMaxFrames)
                .toFloat().coerceIn(valueFrom, valueTo)
            valueLabel.text = frameCountText(value.toInt())
            addOnChangeListener { _, v, _ ->
                valueLabel.text = frameCountText(v.toInt())
                DicSettings.setMaxFrames(this@SettingsActivity, v.toInt(), remoteMaxFrames)
            }
        }
        findViewById<ImageButton>(R.id.btnMaxFramesInfo).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_max_frames)
                .setMessage(R.string.setting_max_frames_info)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun wireHelpSupportSection() {
        findViewById<View>(R.id.btnOpenManual).setOnClickListener {
            openExternalUrl(getString(R.string.url_manual))
        }
        findViewById<View>(R.id.btnCommunity).setOnClickListener {
            openExternalUrl(getString(R.string.url_community))
        }
        findViewById<View>(R.id.btnReportBug).setOnClickListener {
            openExternalUrl(getString(R.string.url_report_bug))
        }
        findViewById<View>(R.id.btnRequestFeature).setOnClickListener {
            openExternalUrl(getString(R.string.url_request_feature))
        }
        findViewById<View>(R.id.btnEmailSupport).setOnClickListener { emailSupport() }
    }

    /** Opens a https URL in the browser; toast if nothing can handle it. */
    private fun openExternalUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No browser to open %s", url)
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    /** Opens the mail app pre-filled to support with account + device context. */
    private fun emailSupport() {
        val account = TokenStore.cachedEmail(this) ?: getString(R.string.pending_unknown_account)
        // The blank lines leave the cursor above the diagnostics, so the user
        // writes their question first and the context travels underneath it.
        val body = buildString {
            append("\n\n---\n")
            append("Account: ").append(account).append('\n')
            // Same guard as the Account section: a Keystore that refuses to open
            // must not cost the user their way of reaching support.
            val deviceId = runCatching { DeviceKeyManager(this@SettingsActivity).getDeviceId() }
                .getOrDefault("(unavailable)")
            append("Device ID: ").append(deviceId).append('\n')
            append("App: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" — Android ").append(Build.VERSION.RELEASE)
        }
        val support = getString(R.string.support_email)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:".toUri()
            putExtra(Intent.EXTRA_EMAIL, arrayOf(support))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.help_support_subject))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No email app to contact support")
            Toast.makeText(this, getString(R.string.request_access_none, support), Toast.LENGTH_LONG).show()
        }
    }

    private fun wireFooter() {
        findViewById<View>(R.id.btnAbout).setOnClickListener {
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
                    openExternalUrl(getString(R.string.legal_privacy_url))
                }
                .setNegativeButton(R.string.legal_terms) { _, _ ->
                    openExternalUrl(getString(R.string.legal_terms_url))
                }
                .show()
        }
        findViewById<View>(R.id.btnSignOut).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.logout_confirm_title)
                .setMessage(R.string.logout_confirm_body)
                .setPositiveButton(R.string.action_sign_out) { _, _ ->
                    AuthRepository(this).signOut()
                    AuthRoute.toSignIn(this)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    // ── Formatting ───────────────────────────────────────────────────────

    private fun syncLabel(state: SessionRecord.SyncState): String = when (state) {
        SessionRecord.SyncState.SYNCED -> getString(R.string.badge_synced)
        SessionRecord.SyncState.PENDING -> getString(R.string.badge_pending)
        SessionRecord.SyncState.LOCAL_ONLY -> getString(R.string.badge_local)
        SessionRecord.SyncState.FAILED -> getString(R.string.badge_not_backed_up)
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= BYTES_PER_GB -> String.format(Locale.US, "%.1f GB", bytes / BYTES_PER_GB.toDouble())
        bytes >= BYTES_PER_MB -> String.format(Locale.US, "%.0f MB", bytes / BYTES_PER_MB.toDouble())
        else -> String.format(Locale.US, "%.0f KB", bytes / BYTES_PER_KB)
    }

    private fun frameCountText(value: Int): String = String.format(Locale.US, "%d", value)

    private companion object {
        const val CHEVRON_EXPANDED_DEG = 180f
        const val BYTES_PER_KB = 1024.0
        const val BYTES_PER_MB = 1_048_576L
        const val BYTES_PER_GB = 1_073_741_824L
        const val ZIP_MIME = "application/zip"
        const val JSON_MIME = "application/json"
        const val PERCENT_MAX = 100

        /** Snackbar shows for exactly as long as the delete stays cancellable. */
        val UNDO_WINDOW_MS = TimeUnit.SECONDS.toMillis(BackupDeleteWorker.UNDO_WINDOW_SECONDS).toInt()
    }
}
