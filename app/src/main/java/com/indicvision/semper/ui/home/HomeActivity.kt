// Home screen wires many list/menu/callback bindings in onCreate; kept together
// for locality, so LongMethod / TooManyFunctions are suppressed for this file.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.indicvision.semper.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.data.CloudSync
import com.indicvision.semper.data.CoachPrefs
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity
import com.indicvision.semper.ui.common.CoachMarkController
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.MediaSourceChooser
import com.indicvision.semper.ui.limit.SessionLimitActivity
import com.indicvision.semper.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var adapter: SessionListAdapter
    private lateinit var selection: SessionSelectionController
    private lateinit var fab: FloatingActionButton
    private lateinit var tvHomeQuota: TextView

    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = selection.clearSelection()
    }

    /** Confirm before leaving Home (and the app). Selection-mode back is separate. */
    private val exitAppCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            MaterialAlertDialogBuilder(this@HomeActivity)
                .setTitle(R.string.exit_indic_title)
                .setMessage(R.string.exit_indic_message)
                .setPositiveButton(R.string.exit) { _, _ -> finish() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
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
        // bar, otherwise the bar swallows taps on the settings gear. The
        // selection bar replaces the title row, so it needs the same inset.
        Insets.padTop(findViewById(R.id.homeTopBar))
        Insets.padTop(findViewById(R.id.homeSelectionBar))

        list = findViewById(R.id.sessionList)
        emptyState = findViewById(R.id.emptyState)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        tvHomeQuota = findViewById(R.id.tvHomeQuota)
        swipeRefresh.setColorSchemeResources(R.color.sky_primary)
        // Pull down = deep re-check: verify the blobs really exist in Drive,
        // not just that the backend's index says so.
        swipeRefresh.setOnRefreshListener { refresh(deep = true) }
        list.layoutManager = LinearLayoutManager(this)

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
        findViewById<ImageButton>(R.id.btnHomeSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnEmptyRestore).setOnClickListener {
            findViewById<ImageButton>(R.id.btnHomeSettings).performClick()
        }

        fab.post {
            CoachMarkController(this).maybeShow(
                CoachPrefs.Screen.HOME,
                listOf(
                    CoachMarkController.Step(
                        fab,
                        getString(R.string.coach_home_fab),
                    ),
                ),
            )
        }

        // Adapter callbacks close over selection; both must exist before the
        // list attaches so a early bind cannot hit an uninitialized controller.
        adapter = SessionListAdapter(
            isSelected = { id -> selection.isSelected(id) },
            onClick = { record ->
                if (selection.inSelectionMode) {
                    selection.toggleSelection(record)
                } else {
                    openSession(record)
                }
            },
            onLongClick = { record ->
                if (selection.inSelectionMode) {
                    selection.toggleSelection(record)
                } else {
                    selection.startSelection(record)
                }
            },
            onBadgeClick = { record -> retryOrBackup(record) },
        )
        selection = SessionSelectionController(
            activity = this,
            adapter = adapter,
            topBar = findViewById(R.id.homeTopBar),
            selectionBar = findViewById(R.id.homeSelectionBar),
            selectionCount = findViewById(R.id.tvSelectionCount),
            btnSelectionRename = findViewById(R.id.btnSelectionRename),
            selectAllBox = findViewById(R.id.cbSelectionAll),
            fab = fab,
            backCallback = backCallback,
            onRefresh = { refresh() },
            onDeviceOnlyDeleted = { showDeviceOnlyKeptSnackbar() },
        )
        selection.bindBarActions(
            btnClose = findViewById(R.id.btnSelectionClose),
            btnDelete = findViewById(R.id.btnSelectionDelete),
        )
        list.adapter = adapter

        // Exit confirm is always registered; selection back is layered on top and
        // enabled only while something is selected (LIFO: last added runs first).
        onBackPressedDispatcher.addCallback(this, exitAppCallback)
        onBackPressedDispatcher.addCallback(this, backCallback)

        maybeShowBetaNotice()
        // Cold start / return with an already-full quota → persistent support screen.
        lifecycleScope.launch {
            val localCount = withContext(Dispatchers.IO) {
                SessionStore.list(this@HomeActivity).size
            }
            TokenStore.refreshSessionLimit(this@HomeActivity, localCount)
            if (TokenStore.isSessionLimitReached(this@HomeActivity)) openSessionLimitScreen()
        }
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
            updateQuotaIndicator(sessions.size)
            // A refresh can drop rows out from under a selection.
            selection.updateSelectionBar()
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

    private fun updateQuotaIndicator(localSessionCount: Int) {
        val max = TokenStore.effectiveQuotaMax(this)
        val used = TokenStore.quotaUsed(this).coerceAtLeast(localSessionCount)
        if (max <= 0) {
            tvHomeQuota.isVisible = false
            return
        }
        tvHomeQuota.isVisible = true
        tvHomeQuota.text = resources.getQuantityString(R.plurals.home_quota_fmt, used, used, max)
        tvHomeQuota.setTextColor(
            getColor(
                if (used >= max) R.color.semantic_danger else R.color.text_secondary,
            ),
        )
        tvHomeQuota.setOnClickListener {
            if (TokenStore.isSessionLimitReached(this)) {
                openSessionLimitScreen()
            } else {
                findViewById<ImageButton>(R.id.btnHomeSettings).performClick()
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
                        resources.getQuantityString(R.plurals.cloud_resync_fmt, outcome.repaired, outcome.repaired),
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

    private fun openSession(record: SessionRecord) = SessionOpenHelper.openOrExplain(this, record)

    /** Retry a failed/pending upload, or back up a local-only session when cloud is on. */
    private fun retryOrBackup(record: SessionRecord) {
        when (record.syncState) {
            SessionRecord.SyncState.FAILED, SessionRecord.SyncState.PENDING -> {
                SessionStore.setSyncState(this, record.id, SessionRecord.SyncState.PENDING)
                CloudSync.enqueueUpload(this, record.id)
                adapter.rebindRow(record.id)
                Toast.makeText(this, R.string.cloud_retry_backup, Toast.LENGTH_SHORT).show()
            }
            SessionRecord.SyncState.LOCAL_ONLY -> if (DicSettings.saveToCloud(this)) {
                SessionStore.setSyncState(this, record.id, SessionRecord.SyncState.PENDING)
                CloudSync.enqueueUpload(this, record.id)
                adapter.rebindRow(record.id)
                Toast.makeText(this, R.string.cloud_backup_now, Toast.LENGTH_SHORT).show()
            } else {
                findViewById<ImageButton>(R.id.btnHomeSettings).performClick()
            }
            SessionRecord.SyncState.SYNCED -> findViewById<ImageButton>(R.id.btnHomeSettings).performClick()
        }
    }

    private fun showDeviceOnlyKeptSnackbar() {
        Snackbar.make(findViewById(R.id.homeRoot), R.string.delete_device_kept_snackbar, Snackbar.LENGTH_LONG)
            .setAction(R.string.delete_device_restore_action) {
                findViewById<ImageButton>(R.id.btnHomeSettings).performClick()
            }
            .show()
    }

    override fun onDestroy() {
        if (::adapter.isInitialized) adapter.clearThumbCache()
        super.onDestroy()
    }
}
