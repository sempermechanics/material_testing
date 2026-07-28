// The settings page wires every section in one screen; kept together for
// locality, so LongMethod / TooManyFunctions are suppressed for this file.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.rafad.indicvisiondic.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.rafad.indicvisiondic.BuildConfig
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.AuthRepository
import com.rafad.indicvisiondic.data.BackupDeleteWorker
import com.rafad.indicvisiondic.data.CloudRestore
import com.rafad.indicvisiondic.data.CloudSync
import com.rafad.indicvisiondic.data.DeviceKeyManager
import com.rafad.indicvisiondic.data.DicSettings
import com.rafad.indicvisiondic.data.SessionEverythingExporter
import com.rafad.indicvisiondic.data.SessionRecord
import com.rafad.indicvisiondic.data.SessionStore
import com.rafad.indicvisiondic.data.net.CloudSessionDto
import com.rafad.indicvisiondic.data.net.TokenStore
import com.rafad.indicvisiondic.ui.admin.AdminActivity
import com.rafad.indicvisiondic.ui.common.AuthRoute
import com.rafad.indicvisiondic.ui.common.Insets
import com.rafad.indicvisiondic.ui.home.SessionOpenHelper
import com.rafad.indicvisiondic.ui.viewer.SaveExportActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Settings: account, cloud preferences, per-analysis data management, data
 * export/erasure and analysis defaults. A page rather than a sheet — Home
 * refreshes in `onResume`, so anything changed here is reflected on return.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var analysesList: LinearLayout
    private lateinit var analysesProgress: ProgressBar
    private lateinit var analysesState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // Edge-to-edge: without this the status bar swallows taps on the back arrow.
        Insets.padTop(findViewById(R.id.settingsTopBar))
        Insets.padBottom(findViewById(R.id.settingsScroll))

        findViewById<ImageButton>(R.id.btnSettingsBack).setOnClickListener { finish() }

        analysesList = findViewById(R.id.analysesDataList)
        analysesProgress = findViewById(R.id.progressAnalysesData)
        analysesState = findViewById(R.id.tvAnalysesDataState)

        wireCollapsible(R.id.headerAccount, R.id.bodyAccount, R.id.ivAccountChevron)
        wireCollapsible(R.id.headerCloud, R.id.bodyCloud, R.id.ivCloudChevron)
        wireCollapsible(R.id.headerAnalysesData, R.id.bodyAnalysesData, R.id.ivAnalysesDataChevron)
        wireCollapsible(R.id.headerYourData, R.id.bodyYourData, R.id.ivYourDataChevron)
        wireCollapsible(R.id.headerAnalysisPrefs, R.id.bodyAnalysisPrefs, R.id.ivAnalysisPrefsChevron)

        wireAccountSection()
        wireCloudSection()
        wireAnalysesDataSection()
        wireYourDataSection()
        wirePreferencesSection()
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
            getString(R.string.account_device_id_fmt, shortDeviceId(deviceId))

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
                .setMessage(getString(R.string.cloud_backfill_body, localOnly.size))
                .setPositiveButton(R.string.cloud_backfill_confirm) { _, _ ->
                    localOnly.forEach { CloudSync.enqueueUpload(this@SettingsActivity, it.id, allowMetered = true) }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    // ── Analyses data management ─────────────────────────────────────────

    private fun wireAnalysesDataSection() {
        analysesProgress.isVisible = true
        analysesState.isVisible = false
        analysesList.removeAllViews()

        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) { SessionStore.list(this@SettingsActivity) }
            val result = CloudRestore.listRestorable(this@SettingsActivity)
            analysesProgress.isVisible = false

            cloudStateMessage(result)?.let {
                analysesState.isVisible = true
                analysesState.text = it
            }
            val cloud = (result as? CloudRestore.ListResult.Ready)?.sessions.orEmpty()
            val entries = AnalysisEntries.merge(records, cloud)
            if (entries.isEmpty()) {
                analysesState.isVisible = true
                analysesState.setText(R.string.analyses_data_empty)
            }
            entries.forEach { addAnalysisRow(it) }
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

    private fun addAnalysisRow(entry: AnalysisEntry) {
        val row = layoutInflater.inflate(R.layout.item_analysis_data, analysesList, false)
        row.findViewById<TextView>(R.id.tvAnalysisName).text = entry.name
        row.findViewById<TextView>(R.id.tvAnalysisState).text = stateLine(entry)

        val cloud = entry.cloud
        val record = entry.record
        if (cloud != null) {
            row.findViewById<ImageButton>(R.id.btnAnalysisRestore).apply {
                isVisible = true
                setOnClickListener {
                    CloudRestore.enqueueRestore(this@SettingsActivity, cloud.sessionId)
                    Toast.makeText(this@SettingsActivity, R.string.restore_background_note, Toast.LENGTH_SHORT).show()
                }
            }
            row.findViewById<ImageButton>(R.id.btnAnalysisDelete).apply {
                isVisible = true
                setOnClickListener {
                    if (record != null) {
                        showDeleteBackupChoice(record, cloud, row)
                    } else {
                        confirmDeleteCloudBackup(cloud, entry.name, row)
                    }
                }
            }
        } else if (record != null) {
            wireBackupButton(row.findViewById(R.id.btnAnalysisBackup), record)
        }

        if (record != null) row.setOnClickListener { SessionOpenHelper.openOrExplain(this, record) }
        analysesList.addView(row)
    }

    private fun stateLine(entry: AnalysisEntry): String = when (entry.location) {
        AnalysisLocation.CLOUD_ONLY ->
            getString(R.string.analysis_state_cloud_only_fmt, humanSize(entry.cloud?.totalBytes ?: 0L))
        AnalysisLocation.PHONE_AND_CLOUD ->
            getString(R.string.analysis_state_phone_and_cloud_fmt, humanSize(entry.cloud?.totalBytes ?: 0L))
        AnalysisLocation.PHONE_ONLY -> getString(R.string.analysis_state_phone_only)
        // The cloud could not confirm this one: keep its badge rather than
        // claiming the backup is gone.
        AnalysisLocation.PHONE_SYNC_STATE ->
            entry.record?.let { syncLabel(it.syncState) }.orEmpty()
    }

    private fun wireBackupButton(button: MaterialButton, record: SessionRecord) {
        val label = when (record.syncState) {
            SessionRecord.SyncState.FAILED, SessionRecord.SyncState.PENDING -> R.string.cloud_retry_backup
            SessionRecord.SyncState.LOCAL_ONLY ->
                if (DicSettings.saveToCloud(this)) R.string.cloud_backup_now else return
            // Backed up, but this run could not list the cloud: offer nothing
            // rather than a "back up" that would duplicate an existing copy.
            SessionRecord.SyncState.SYNCED -> return
        }
        button.isVisible = true
        button.setText(label)
        button.setOnClickListener {
            SessionStore.setSyncState(this, record.id, SessionRecord.SyncState.PENDING)
            CloudSync.enqueueUpload(this, record.id, allowMetered = true)
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
            wireAnalysesDataSection()
        }
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
        analysesList.removeView(row)
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
        findViewById<View>(R.id.btnDeleteAccount).setOnClickListener { confirmDeleteAccount() }
    }

    private fun exportMyData() {
        Toast.makeText(this, R.string.export_data_working, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val export = SessionEverythingExporter.exportMasterZip(this@SettingsActivity)
            if (export == null) {
                Toast.makeText(this@SettingsActivity, R.string.export_data_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val file = export.file
            val uri = FileProvider.getUriForFile(this@SettingsActivity, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = ZIP_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, getString(R.string.export_data_share)).apply {
                putExtra(
                    Intent.EXTRA_INITIAL_INTENTS,
                    arrayOf(SaveExportActivity.intent(this@SettingsActivity, file, ZIP_MIME)),
                )
            }
            startActivity(chooser)
        }
    }

    private fun confirmDeleteAccount() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_account_title)
            .setMessage(R.string.delete_account_body)
            .setPositiveButton(R.string.delete_account_confirm) { _, _ ->
                lifecycleScope.launch {
                    if (CloudSync.deleteAccount(this@SettingsActivity)) {
                        Toast.makeText(this@SettingsActivity, R.string.delete_account_done, Toast.LENGTH_LONG).show()
                        AuthRoute.toSignIn(this@SettingsActivity)
                    } else {
                        Toast.makeText(this@SettingsActivity, R.string.delete_account_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun wirePreferencesSection() {
        val valueLabel = findViewById<TextView>(R.id.tvMaxFramesValue)
        findViewById<Slider>(R.id.sliderMaxFrames).apply {
            value = DicSettings.maxFrames(this@SettingsActivity).toFloat()
            valueLabel.text = frameCountText(value.toInt())
            addOnChangeListener { _, v, _ ->
                valueLabel.text = frameCountText(v.toInt())
                DicSettings.setMaxFrames(this@SettingsActivity, v.toInt())
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

    private fun wireFooter() {
        findViewById<View>(R.id.btnAbout).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage("inDIC v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                .setPositiveButton(android.R.string.ok, null)
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

    /** Device ids are long hashes; the head is enough to recognise this phone. */
    private fun shortDeviceId(id: String): String =
        if (id.length > DEVICE_ID_CHARS) id.take(DEVICE_ID_CHARS) + "…" else id

    private companion object {
        const val CHEVRON_EXPANDED_DEG = 180f
        const val DEVICE_ID_CHARS = 12
        const val BYTES_PER_KB = 1024.0
        const val BYTES_PER_MB = 1_048_576L
        const val BYTES_PER_GB = 1_073_741_824L
        const val ZIP_MIME = "application/zip"

        /** Snackbar shows for exactly as long as the delete stays cancellable. */
        val UNDO_WINDOW_MS = TimeUnit.SECONDS.toMillis(BackupDeleteWorker.UNDO_WINDOW_SECONDS).toInt()
    }
}
