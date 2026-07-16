package com.rafad.indicvisiondic.ui.home

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.rafad.indicvisiondic.BuildConfig
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.R
import android.widget.Toast
import androidx.core.content.FileProvider
import com.rafad.indicvisiondic.data.CloudSync
import com.rafad.indicvisiondic.data.DicSettings
import com.rafad.indicvisiondic.data.SessionRecord
import com.rafad.indicvisiondic.data.SessionStore
import com.rafad.indicvisiondic.data.net.TokenStore
import com.rafad.indicvisiondic.ui.analysis.StaticAnalysisActivity
import com.rafad.indicvisiondic.ui.auth.AuthActivity
import com.rafad.indicvisiondic.ui.viewer.ResultViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home: the record of every analysis done on this phone (metadata from
 * [SessionStore]; heavy files per session dir, full copies in the cloud once
 * synced). The + button is the single entry point for a new analysis — it
 * opens the system media picker, and the selection type (image vs video)
 * decides the next screen. The gear opens the behavioral settings drawer.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var emptyState: android.view.View
    private val adapter = SessionAdapter()

    private val pickReference =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            val mime = contentResolver.getType(uri) ?: ""
            val intent = Intent(this, StaticAnalysisActivity::class.java)
            if (mime.startsWith("video/")) {
                intent.putExtra(DicKeys.PICKED_VIDEO_URI, uri.toString())
            } else {
                intent.putExtra(DicKeys.PICKED_REF_URI, uri.toString())
            }
            startActivity(intent)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Edge-to-edge (enforced on API 35+): drop the header below the status
        // bar, otherwise the bar swallows taps on the settings gear.
        com.rafad.indicvisiondic.ui.Insets.padTop(findViewById(R.id.homeTopBar))

        list = findViewById(R.id.sessionList)
        emptyState = findViewById(R.id.emptyState)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabNewAnalysis).setOnClickListener {
            android.widget.Toast.makeText(this, R.string.picker_select_reference, android.widget.Toast.LENGTH_LONG).show()
            pickReference.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        }
        findViewById<ImageButton>(R.id.btnHomeSettings).setOnClickListener { showSettingsDrawer() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) { SessionStore.list(this@HomeActivity) }
            adapter.submit(sessions)
            emptyState.isVisible = sessions.isEmpty()
            reconcileWithCloud()
        }
    }

    /**
     * Ask the backend what is actually backed up and repair any drift — a
     * session whose cloud copy was deleted stops claiming "Synced" and is
     * re-queued for upload. Best-effort: offline/unconfigured leaves state alone.
     */
    private suspend fun reconcileWithCloud() {
        val report = CloudSync.reconcile(this@HomeActivity) ?: return
        if (report.repaired > 0) {
            // The rows changed underneath us — show the corrected state.
            val sessions = withContext(Dispatchers.IO) { SessionStore.list(this@HomeActivity) }
            adapter.submit(sessions)
            Toast.makeText(
                this,
                getString(R.string.cloud_resync_fmt, report.repaired),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // ── Row actions ──────────────────────────────────────────────────────

    private fun openSession(record: SessionRecord) {
        if (!record.hasLocalData()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.session_data_gone_title)
                .setMessage(R.string.session_data_gone_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val intent = Intent(this, ResultViewerActivity::class.java).apply {
            putExtra(DicKeys.IMG_W, record.imgW)
            putExtra(DicKeys.IMG_H, record.imgH)
            putExtra(DicKeys.STEP, record.step)
            putExtra(DicKeys.REF_NAME, record.refName)
            putExtra(DicKeys.REF_PATH, record.refPath)
            putExtra(DicKeys.BATCH_DIR_PATH, record.sessionDir)
            putStringArrayListExtra(DicKeys.DEF_FILE_NAMES, ArrayList(record.defNames))
            putExtra(DicKeys.SESSION_ID, record.id)
            putExtra(DicKeys.SESSION_LOCAL_ID, record.id)
            putExtra(DicKeys.SUBSET_SIZE, record.subset)
            putExtra(DicKeys.STRAIN_WINDOW, record.strainWindow)
            putExtra(DicKeys.STRAIN_METHOD, "VSG")
            putExtra(DicKeys.ENGINE_STATS, record.engineStats.toFloatArray())
            putExtra(DicKeys.ROI_X, record.roiX)
            putExtra(DicKeys.ROI_Y, record.roiY)
            putExtra(DicKeys.ROI_W, record.roiW)
            putExtra(DicKeys.ROI_H, record.roiH)
        }
        startActivity(intent)
    }

    private fun showRowActions(record: SessionRecord) {
        val actions = arrayOf(getString(R.string.action_rename), getString(R.string.action_delete))
        MaterialAlertDialogBuilder(this)
            .setTitle(record.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> promptRename(record)
                    1 -> confirmDelete(record)
                }
            }
            .show()
    }

    private fun promptRename(record: SessionRecord) {
        val input = EditText(this).apply { setText(record.name) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    SessionStore.rename(this, record.id, newName)
                    refresh()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Delete an analysis. When a cloud backup exists the user gets an explicit
     * choice, with full erasure (device + cloud) as the primary action — the
     * GDPR right-to-erasure path.
     */
    private fun confirmDelete(record: SessionRecord) {
        val hasCloudCopy = record.syncState == SessionRecord.SyncState.SYNCED ||
            record.cloudSessionId.isNotBlank()

        if (!hasCloudCopy) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_body_local)
                .setPositiveButton(R.string.action_delete) { _, _ -> eraseEverywhere(record) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_confirm_body_cloud)
            .setPositiveButton(R.string.delete_everywhere) { _, _ -> eraseEverywhere(record) }
            .setNeutralButton(R.string.delete_device_only) { _, _ ->
                lifecycleScope.launch {
                    CloudSync.eraseLocalOnly(this@HomeActivity, record.id)
                    Toast.makeText(this@HomeActivity, R.string.delete_device_done, Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun routeToSignIn() {
        val intent = Intent(this@HomeActivity, AuthActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    /**
     * GDPR data portability: fetch a machine-readable copy of everything the
     * backend holds about this account and hand it to the share sheet, so the
     * user can keep it wherever they like.
     */
    private fun exportMyData() {
        Toast.makeText(this, R.string.export_data_working, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val file = CloudSync.exportAccountData(this@HomeActivity)
            if (file == null) {
                Toast.makeText(this@HomeActivity, R.string.export_data_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                this@HomeActivity, "$packageName.fileprovider", file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.export_data_share)))
        }
    }

    /**
     * GDPR account deletion. Spells out exactly what is erased, and only claims
     * success once the cloud has actually confirmed it.
     */
    private fun confirmDeleteAccount() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_account_title)
            .setMessage(R.string.delete_account_body)
            .setPositiveButton(R.string.delete_account_confirm) { _, _ ->
                lifecycleScope.launch {
                    if (CloudSync.deleteAccount(this@HomeActivity)) {
                        Toast.makeText(this@HomeActivity, R.string.delete_account_done, Toast.LENGTH_LONG).show()
                        routeToSignIn()
                    } else {
                        // Nothing was deleted — keep the user signed in and say so.
                        Toast.makeText(this@HomeActivity, R.string.delete_account_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun eraseEverywhere(record: SessionRecord) {
        lifecycleScope.launch {
            when (CloudSync.eraseEverywhere(this@HomeActivity, record.id)) {
                CloudSync.EraseResult.ERASED_EVERYWHERE ->
                    Toast.makeText(this@HomeActivity, R.string.delete_everywhere_done, Toast.LENGTH_SHORT).show()
                // Nothing was deleted — don't imply the cloud copy is gone.
                CloudSync.EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE ->
                    Toast.makeText(this@HomeActivity, R.string.delete_cloud_failed, Toast.LENGTH_LONG).show()
            }
            refresh()
        }
    }

    // ── Settings drawer ──────────────────────────────────────────────────

    private fun showSettingsDrawer() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_home_settings, null)
        sheet.setContentView(view)

        view.findViewById<SwitchMaterial>(R.id.switchSaveCloud).apply {
            isChecked = DicSettings.saveToCloud(this@HomeActivity)
            setOnCheckedChangeListener { _, v -> DicSettings.setSaveToCloud(this@HomeActivity, v) }
        }
        view.findViewById<SwitchMaterial>(R.id.switchKeepRerun).apply {
            isChecked = DicSettings.keepEveryRerun(this@HomeActivity)
            setOnCheckedChangeListener { _, v -> DicSettings.setKeepEveryRerun(this@HomeActivity, v) }
        }

        val valueLabel = view.findViewById<TextView>(R.id.tvMaxFramesValue)
        view.findViewById<Slider>(R.id.sliderMaxFrames).apply {
            value = DicSettings.maxFrames(this@HomeActivity).toFloat()
            valueLabel.text = value.toInt().toString()
            addOnChangeListener { _, v, _ ->
                valueLabel.text = v.toInt().toString()
                DicSettings.setMaxFrames(this@HomeActivity, v.toInt())
            }
        }
        view.findViewById<ImageButton>(R.id.btnMaxFramesInfo).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_max_frames)
                .setMessage(R.string.setting_max_frames_info)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        view.findViewById<TextView>(R.id.tvAccountEmail).text =
            TokenStore.cachedEmail(this) ?: ""

        view.findViewById<android.view.View>(R.id.btnRestoreCloud).setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this@HomeActivity, com.rafad.indicvisiondic.ui.restore.RestoreActivity::class.java))
        }

        // Admin entry: only for accounts whose backend role is admin.
        view.findViewById<android.view.View>(R.id.btnAdmin).apply {
            visibility = if (TokenStore.isAdmin(this@HomeActivity)) android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener {
                sheet.dismiss()
                startActivity(Intent(this@HomeActivity, com.rafad.indicvisiondic.ui.admin.AdminActivity::class.java))
            }
        }

        view.findViewById<android.view.View>(R.id.btnAbout).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage("inDIC v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        view.findViewById<android.view.View>(R.id.btnSignOut).setOnClickListener {
            sheet.dismiss()
            TokenStore.clear(this)
            routeToSignIn()
        }
        view.findViewById<android.view.View>(R.id.btnExportData).setOnClickListener {
            sheet.dismiss()
            exportMyData()
        }
        view.findViewById<android.view.View>(R.id.btnDeleteAccount).setOnClickListener {
            sheet.dismiss()
            confirmDeleteAccount()
        }

        sheet.show()
    }

    // ── List adapter ─────────────────────────────────────────────────────

    private inner class SessionAdapter : RecyclerView.Adapter<SessionAdapter.Holder>() {
        private var items: List<SessionRecord> = emptyList()
        private val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())

        fun submit(newItems: List<SessionRecord>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class Holder(v: android.view.View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.sessionThumb)
            val title: TextView = v.findViewById(R.id.sessionTitle)
            val subtitle: TextView = v.findViewById(R.id.sessionSubtitle)
            val badge: TextView = v.findViewById(R.id.sessionBadge)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val v = layoutInflater.inflate(R.layout.item_session, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = items[position]
            holder.title.text = r.name
            holder.subtitle.text = buildString {
                append(dateFmt.format(Date(r.updatedAt)))
                append(" · ")
                append(getString(R.string.session_frames_fmt, r.frameCount))
                if (r.headline.isNotBlank()) {
                    append(" · ")
                    append(r.headline)
                }
            }
            holder.badge.text = when (r.syncState) {
                SessionRecord.SyncState.SYNCED -> getString(R.string.badge_synced)
                SessionRecord.SyncState.PENDING -> getString(R.string.badge_pending)
                SessionRecord.SyncState.LOCAL_ONLY -> getString(R.string.badge_local)
                SessionRecord.SyncState.FAILED -> getString(R.string.badge_not_backed_up)
            }
            holder.badge.setTextColor(
                if (r.syncState == SessionRecord.SyncState.FAILED) {
                    getColor(R.color.semantic_danger)
                } else {
                    getColor(R.color.sky_on_container)
                },
            )

            val refFile = File(r.refPath)
            if (refFile.exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                holder.thumb.setImageBitmap(BitmapFactory.decodeFile(r.refPath, opts))
            } else {
                holder.thumb.setImageDrawable(null)
            }

            holder.itemView.setOnClickListener { openSession(r) }
            holder.itemView.setOnLongClickListener {
                showRowActions(r)
                true
            }
        }
    }
}
