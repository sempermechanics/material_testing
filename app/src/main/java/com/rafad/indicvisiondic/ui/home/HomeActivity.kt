package com.rafad.indicvisiondic.ui.home

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.rafad.indicvisiondic.BuildConfig
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.CloudSync
import com.rafad.indicvisiondic.data.DevAuth
import com.rafad.indicvisiondic.data.DicSettings
import com.rafad.indicvisiondic.data.SessionRecord
import com.rafad.indicvisiondic.data.SessionStore
import com.rafad.indicvisiondic.data.net.TokenStore
import com.rafad.indicvisiondic.ui.analysis.StaticAnalysisActivity
import com.rafad.indicvisiondic.ui.auth.AuthActivity
import com.rafad.indicvisiondic.ui.auth.SplashActivity
import com.rafad.indicvisiondic.ui.common.MediaSourceChooser
import com.rafad.indicvisiondic.ui.limit.SessionLimitActivity
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
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private val adapter = SessionAdapter()

    // --- Selection mode -------------------------------------------------
    // Long-press starts it, tap toggles rows while it lasts, and it ends when
    // the last row is deselected. Ids rather than indices, so the set survives
    // a refresh() that reorders or drops rows.
    private lateinit var topBar: android.view.View
    private lateinit var selectionBar: android.view.View
    private lateinit var selectionCount: TextView
    private lateinit var btnSelectionRename: ImageButton
    private lateinit var selectAllBox: com.google.android.material.checkbox.MaterialCheckBox
    private lateinit var fab: FloatingActionButton
    private val selectedIds = linkedSetOf<String>()
    private val inSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = clearSelection()
    }

    /** Source A: the system Photo Picker (gallery / Google Photos). */
    private val pickReference =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            routePickedMedia(uri)
        }

    /** Source B: the Storage Access Framework (Downloads, Drive, on-device files). */
    private val pickDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            routePickedMedia(uri)
        }

    /**
     * Route a picked photo/video into the analysis screen. Shared by both source
     * pickers so the two entry points behave identically; the mime type decides
     * whether we hand off a single reference image or a video to sample frames
     * from.
     */
    private fun routePickedMedia(uri: android.net.Uri?) {
        if (uri == null) return
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
        com.rafad.indicvisiondic.ui.common.Insets.padTop(findViewById(R.id.homeTopBar))

        list = findViewById(R.id.sessionList)
        emptyState = findViewById(R.id.emptyState)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeResources(R.color.sky_primary)
        // Pull down = deep re-check: verify the blobs really exist in Drive,
        // not just that the backend's index says so.
        swipeRefresh.setOnRefreshListener { refresh(deep = true) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        fab = findViewById(R.id.fabNewAnalysis)
        fab.setOnClickListener {
            // At the account's analysis limit, block new work behind the persistent
            // limit screen (email support) instead of letting it fail on upload.
            if (TokenStore.isSessionLimitReached(this)) {
                openSessionLimitScreen()
                return@setOnClickListener
            }
            showSourceChooser()
        }
        findViewById<ImageButton>(R.id.btnHomeSettings).setOnClickListener { showSettingsDrawer() }

        topBar = findViewById(R.id.homeTopBar)
        selectionBar = findViewById(R.id.homeSelectionBar)
        selectionCount = findViewById(R.id.tvSelectionCount)
        btnSelectionRename = findViewById(R.id.btnSelectionRename)
        findViewById<ImageButton>(R.id.btnSelectionClose).setOnClickListener { clearSelection() }
        findViewById<ImageButton>(R.id.btnSelectionDelete).setOnClickListener { confirmDeleteSelected() }
        selectAllBox = findViewById(R.id.cbSelectionAll)
        // setOnClickListener, not setOnCheckedChangeListener: updateSelectionBar
        // drives the checked state, and a change listener would re-enter here
        // every time it did.
        selectAllBox.setOnClickListener {
            // Unticking means "none", which empties the selection and therefore
            // ends selection mode — the same as clearing it.
            if (selectAllBox.isChecked) selectAll() else clearSelection()
        }
        btnSelectionRename.setOnClickListener {
            adapter.selectedRecords().singleOrNull()?.let { promptRename(it) }
        }

        // Back leaves selection mode before it leaves the screen. Enabled only
        // while something is selected, so normal back still exits Home.
        onBackPressedDispatcher.addCallback(this, backCallback)

        maybeShowBetaNotice()
        // Cold start / return with an already-full quota → persistent support screen.
        TokenStore.refreshSessionLimit(this, SessionStore.list(this).size)
        if (TokenStore.isSessionLimitReached(this)) openSessionLimitScreen()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** One-time beta / data-use declaration after the account first reaches Home. */
    private fun maybeShowBetaNotice() {
        if (TokenStore.hasAckedBetaNotice(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.beta_notice_title)
            .setMessage(R.string.beta_notice_body)
            .setCancelable(false)
            .setPositiveButton(R.string.beta_notice_ack) { _, _ ->
                TokenStore.setBetaNoticeAcked(this)
            }
            .show()
    }

    private fun openSessionLimitScreen() {
        startActivity(Intent(this, SessionLimitActivity::class.java))
    }

    /**
     * Ask where to pick the reference from, in a styled sheet we control, before
     * opening the system picker. The system Photo Picker runs in its own window
     * and can't be labelled or overlaid, so the instruction and source choice
     * live here instead — Photos routes to the Photo Picker, Files to the Storage
     * Access Framework (Downloads, Drive, on-device storage).
     */
    private fun showSourceChooser() = MediaSourceChooser.show(
        activity = this,
        titleRes = R.string.new_analysis_title,
        captionRes = R.string.picker_select_reference,
        onPhotos = {
            pickReference.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        },
        onFiles = { pickDocument.launch(arrayOf("image/*", "video/*")) },
    )

    /**
     * @param deep verify blobs really exist in Drive (pull-to-refresh) rather
     *   than trusting the backend index (cheap resume check).
     */
    private fun refresh(deep: Boolean = false) {
        lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) { SessionStore.list(this@HomeActivity) }
            adapter.submit(sessions)
            emptyState.isVisible = sessions.isEmpty()
            // A refresh can drop rows out from under a selection.
            updateSelectionBar()
            // Local count alone can trip the hard-stop flag (before cloud reconcile).
            TokenStore.refreshSessionLimit(this@HomeActivity, sessions.size)
            try {
                reconcileWithCloud(deep)
            } finally {
                // The spinner tracks the cloud check, not the local list read —
                // that's the part worth waiting for.
                swipeRefresh.isRefreshing = false
            }
        }
    }

    /**
     * Ask the backend what is actually backed up and repair any drift — a
     * session whose cloud copy was deleted stops claiming "Synced" and is
     * re-queued for upload.
     *
     * Offline and "not configured" stay silent (that's normal for an
     * offline-first app), but a real backend fault is surfaced: otherwise the
     * badges quietly go stale and the user trusts a backup that isn't there.
     */
    private suspend fun reconcileWithCloud(deep: Boolean) {
        when (val outcome = CloudSync.reconcile(this@HomeActivity, deep = deep)) {
            is CloudSync.Outcome.Ok -> {
                // Record the account's quota so the new-analysis gate and the
                // limit screen reflect the latest server truth.
                val wasLimited = TokenStore.isSessionLimitReached(this)
                val localCount = withContext(Dispatchers.IO) { SessionStore.list(this@HomeActivity).size }
                TokenStore.setQuota(this, outcome.quotaUsed, outcome.quotaMax, localCount)
                // Newly at the cap → open the persistent "email support" screen.
                if (!wasLimited && TokenStore.isSessionLimitReached(this)) {
                    openSessionLimitScreen()
                }
                if (outcome.repaired > 0) {
                    // The rows changed underneath us — show the corrected state.
                    val sessions = withContext(Dispatchers.IO) { SessionStore.list(this@HomeActivity) }
                    adapter.submit(sessions)
                    Toast.makeText(
                        this,
                        getString(R.string.cloud_resync_fmt, outcome.repaired),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            is CloudSync.Outcome.Failed ->
                Toast.makeText(
                    this,
                    getString(R.string.cloud_check_failed_fmt, outcome.reason),
                    Toast.LENGTH_LONG,
                ).show()
            // Normal for an offline-first app — don't nag. Skipped = checked
            // recently (reconcile is throttled to protect the Firestore budget).
            CloudSync.Outcome.Offline, CloudSync.Outcome.Disabled, CloudSync.Outcome.Skipped -> Unit
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

    // ------------------------------------------------------------------
    // Selection mode
    // ------------------------------------------------------------------

    /** Long-press on an unselected list: enters selection mode with that row. */
    private fun startSelection(record: SessionRecord) {
        selectedIds.add(record.id)
        adapter.rebindRow(record.id)
        updateSelectionBar()
    }

    /** Toggles one row; entering/leaving selection mode falls out of the count. */
    private fun toggleSelection(record: SessionRecord) {
        if (!selectedIds.remove(record.id)) selectedIds.add(record.id)
        adapter.rebindRow(record.id)
        updateSelectionBar()
    }

    private fun clearSelection() {
        if (selectedIds.isEmpty()) return
        val cleared = selectedIds.toList()
        selectedIds.clear()
        cleared.forEach { adapter.rebindRow(it) }
        updateSelectionBar()
    }

    private fun selectAll() {
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
    private fun updateSelectionBar() {
        // Rows can disappear under a selection (a refresh, a delete elsewhere);
        // drop ids that no longer exist so the count never lies.
        selectedIds.retainAll(adapter.allIds().toSet())

        val active = inSelectionMode
        selectionBar.isVisible = active
        topBar.isVisible = !active
        backCallback.isEnabled = active
        if (active) fab.hide() else fab.show()
        selectionCount.text = getString(R.string.selection_count_fmt, selectedIds.size)
        btnSelectionRename.isVisible = selectedIds.size == 1
        // Ticked only when every row is in the selection, so the box reports
        // the real state rather than just what was last tapped.
        val allIds = adapter.allIds()
        selectAllBox.isChecked = allIds.isNotEmpty() && selectedIds.size == allIds.size
    }

    /**
     * Bulk delete. Reuses the single-row semantics: full erasure is the primary
     * action, and a device-only option appears when any of the selection has a
     * cloud copy that would otherwise be silently left behind.
     */
    private fun confirmDeleteSelected() {
        val records = adapter.selectedRecords()
        if (records.isEmpty()) return
        if (records.size == 1) {
            confirmDelete(records.first())
            return
        }

        val backedUp = records.count {
            it.syncState == SessionRecord.SyncState.SYNCED || it.cloudSessionId.isNotBlank()
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_confirm_title_multi, records.size))
            .setNegativeButton(R.string.action_cancel, null)

        if (backedUp == 0) {
            dialog.setMessage(R.string.delete_confirm_body_local_multi)
                .setPositiveButton(R.string.action_delete) { _, _ -> eraseSelected(records, cloudToo = true) }
        } else {
            dialog.setMessage(getString(R.string.delete_confirm_body_cloud_multi, backedUp))
                .setPositiveButton(R.string.delete_everywhere) { _, _ -> eraseSelected(records, cloudToo = true) }
                .setNeutralButton(R.string.delete_device_only) { _, _ -> eraseSelected(records, cloudToo = false) }
        }
        dialog.show()
    }

    private fun eraseSelected(records: List<SessionRecord>, cloudToo: Boolean) {
        lifecycleScope.launch {
            Toast.makeText(
                this@HomeActivity,
                getString(R.string.delete_multi_working, records.size),
                Toast.LENGTH_SHORT,
            ).show()

            // Sequential, not parallel: each erase is a cloud round-trip, and
            // the backend is happier with one at a time than N at once.
            var stillInCloud = 0
            for (record in records) {
                if (cloudToo) {
                    val result = CloudSync.eraseEverywhere(this@HomeActivity, record.id)
                    if (result == CloudSync.EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE) stillInCloud++
                } else {
                    CloudSync.eraseLocalOnly(this@HomeActivity, record.id)
                }
            }

            // Report what actually happened — never imply a cloud copy is gone
            // when the backend could not be reached.
            val message = if (stillInCloud > 0) {
                getString(R.string.delete_multi_partial, records.size, stillInCloud)
            } else {
                getString(R.string.delete_multi_done, records.size)
            }
            Toast.makeText(this@HomeActivity, message, Toast.LENGTH_LONG).show()

            clearSelection()
            refresh()
        }
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
        // Under the emulator dev bypass there is nothing to sign in to: go back
        // through the splash, which re-seeds the dev session and returns Home.
        val target = if (DevAuth.active) SplashActivity::class.java else AuthActivity::class.java
        val intent = Intent(this@HomeActivity, target)
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
                this@HomeActivity,
                "$packageName.fileprovider",
                file,
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
            com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
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

        fun allIds(): List<String> = items.map { it.id }

        /** Redraws one row by id — selection changes never touch the whole list. */
        fun rebindRow(id: String) {
            val index = items.indexOfFirst { it.id == id }
            if (index >= 0) notifyItemChanged(index)
        }

        /** The selected rows, in list order. */
        fun selectedRecords(): List<SessionRecord> = items.filter { it.id in selectedIds }

        inner class Holder(v: android.view.View) : RecyclerView.ViewHolder(v) {
            val card: com.google.android.material.card.MaterialCardView =
                v.findViewById(R.id.sessionCard)
            val thumb: ImageView = v.findViewById(R.id.sessionThumb)
            val check: ImageView = v.findViewById(R.id.sessionCheck)
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

            val selected = r.id in selectedIds
            holder.check.isVisible = selected
            holder.card.setCardBackgroundColor(
                getColor(if (selected) R.color.sky_container else R.color.surface_muted),
            )
            holder.card.strokeColor =
                getColor(if (selected) R.color.sky_primary else R.color.surface_outline)

            // Outside selection mode a tap opens the analysis and a long-press
            // starts selecting; inside it, every tap just toggles a row.
            holder.itemView.setOnClickListener {
                if (inSelectionMode) toggleSelection(r) else openSession(r)
            }
            holder.itemView.setOnLongClickListener {
                if (inSelectionMode) toggleSelection(r) else startSelection(r)
                true
            }
        }
    }
}
