// Home owns its row actions, refreshes and pickers as small private steps.
@file:Suppress("TooManyFunctions")

package com.sempermechanics.semper.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.cloud.CloudBackupListing
import com.sempermechanics.semper.data.cloud.SessionDeletes
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.policy.AppPolicy
import com.sempermechanics.semper.data.policy.VersionVerdict
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.isRestorable
import com.sempermechanics.semper.databinding.ActivityHomeBinding
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisNavHelper
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.auth.AuthRoute
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.common.dialog.CrispToast
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.transfer.DeleteFeedback
import com.sempermechanics.semper.ui.common.transfer.RestoreRequest
import com.sempermechanics.semper.ui.limit.UpdateRequiredActivity
import com.sempermechanics.semper.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home: the record of every analysis done on this phone (metadata from
 * [SessionStore]; heavy files per session dir, full copies in the cloud once
 * synced). The + button opens the import source chooser straight away — there is
 * one acquisition path. The gear opens the behavioral settings drawer.
 */
@MainThread
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var adapter: SessionListAdapter
    private lateinit var selection: SessionSelectionController
    private lateinit var cloudBackups: CloudBackupsCard
    private lateinit var quotaCard: HomeQuotaCard
    private lateinit var backupBadge: BackupBadgeActions

    private lateinit var deleteFeedback: DeleteFeedback

    /** The list, queued deletes, offered backups and restores; kept across a rotation. */
    private val viewModel: HomeViewModel by viewModels()

    /** The cloud check behind each [refresh], one at a time; built once [quotaCard] is. */
    private lateinit var cloudCheck: HomeCloudCheck

    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = selection.clearSelection()
    }

    /** Confirm before leaving Home (and the app). Selection-mode back is separate. */
    private val exitAppCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            Dialogs.confirm(
                this@HomeActivity,
                getText(R.string.exit_semper_title),
                null,
                R.string.exit,
            ) {
                finish()
            }
        }
    }

    /** The + button's source chooser and Re-run; registers its launchers, so built with the screen. */
    private val newAnalysis = HomeNewAnalysis(this) { quotaCard.openLimitScreenIfReached() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        newAnalysis.saveState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        newAnalysis.restoreState(savedInstanceState)
        window.decorView.post { reportFullyDrawn() }

        // Edge-to-edge (enforced on API 35+): drop the header below the status
        // bar, otherwise the bar swallows taps on the settings gear. The
        // selection bar replaces the title row, so it needs the same inset.
        Insets.padTop(binding.homeTopBar)
        Insets.padTop(binding.homeSelectionBar)

        cloudBackups = cloudBackupsCard()
        wirePullToRefresh()
        quotaCard = HomeQuotaCard(
            activity = this,
            quotaView = binding.tvHomeQuota,
            licenseView = binding.tvHomeLicense,
            openSettings = { binding.btnHomeSettings.performClick() },
        )
        cloudCheck = HomeCloudCheck(
            activity = this,
            viewModel = viewModel,
            quotaCard = quotaCard,
            showsCloudState = ::showsCloudState,
            onBackupsListed = ::updateCloudBackups,
            stopSpinner = { binding.swipeRefresh.isRefreshing = false },
        )
        wireButtons()

        // Beta notice, then consent, then the coach mark: one overlay at a time,
        // and the diagnostics choice must be made before anything is collected.
        FirstRunPrompts(this).show(binding.fabNewAnalysis)

        wireSessionList()

        // Exit confirm is always registered; selection back is layered on top and
        // enabled only while something is selected (LIFO: last added runs first).
        onBackPressedDispatcher.addCallback(this, exitAppCallback)
        onBackPressedDispatcher.addCallback(this, backCallback)

        openLimitScreenIfFull()
        if (savedInstanceState == null) nudgeIfUpdateRecommended()
        HomeTransferWatch(this, adapter, quotaCard, ::showsCloudState) { refresh() }.observe()
        deleteFeedback.observe()
        observeViewModel()
    }

    /** Each list read, and each restore's and erase's outcome once, on whichever Home is current. */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.list.collect { list -> list?.let(::showList) } }
                launch { viewModel.eraseOutcomes.collect { selection.showEraseOutcome(it) } }
                viewModel.restoreOutcomes.collect { showRestoreOutcome(it) }
            }
        }
    }

    private fun cloudBackupsCard() = CloudBackupsCard(
        card = binding.homeCloudBackups,
        text = binding.tvCloudBackups,
        restoreButton = binding.btnCloudBackupsRestore,
        hideButton = binding.btnCloudBackupsHide,
        onRestore = { targets -> viewModel.restore { targets } },
        onHide = { backups -> hideCloudBackups(backups) },
    )

    private fun wirePullToRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.sky_primary)
        // Pull down = deep re-check: verify the blobs really exist in Drive,
        // not just that the backend's index says so.
        binding.swipeRefresh.setOnRefreshListener { refresh(deep = true) }
        binding.sessionList.layoutManager = LinearLayoutManager(this)
    }

    /** The + button, the gear, and the empty state's button (which is the + button). */
    private fun wireButtons() {
        val fab = binding.fabNewAnalysis
        HomeFabLayout.pinAtNineTenths(binding.homeRoot, fab, binding.sessionList)
        // btnEmptyRestore delegates here via performClick(), so both entry
        // points are covered by this one listener.
        fab.setOnClickListener { newAnalysis.start() }
        binding.btnHomeSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnEmptyRestore.setOnClickListener {
            fab.performClick()
        }
    }

    /**
     * The list, its selection and its delete feedback. Adapter callbacks close
     * over selection; both must exist before the list attaches so an early
     * bind cannot hit an uninitialized controller.
     */
    private fun wireSessionList() {
        adapter = sessionListAdapter()
        backupBadge = BackupBadgeActions(this, adapter) { binding.btnHomeSettings.performClick() }
        selection = selectionController()
        deleteFeedback = DeleteFeedback(this, binding.homeRoot) {
            viewModel.clearQueuedDeletes()
            refresh(reconcile = false)
        }
        selection.bindBarActions(
            btnClose = binding.btnSelectionClose,
            btnDelete = binding.btnSelectionDelete,
        )
        binding.sessionList.adapter = adapter
    }

    private fun sessionListAdapter() = SessionListAdapter(
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
        onBadgeClick = { record -> backupBadge.retryOrBackup(record) },
    )

    private fun selectionController() = SessionSelectionController(
        activity = this,
        adapter = adapter,
        topBar = binding.homeTopBar,
        selectionBar = binding.homeSelectionBar,
        selectionCount = binding.tvSelectionCount,
        btnSelectionRename = binding.btnSelectionRename,
        btnSelectionRestore = binding.btnSelectionRestore,
        btnSelectionRerun = binding.btnSelectionRerun,
        selectAllBox = binding.cbSelectionAll,
        fab = binding.fabNewAnalysis,
        backCallback = backCallback,
        onRefresh = { refresh() },
        onEraseLocally = { records, deviceOnly -> viewModel.eraseLocally(records, deviceOnly) },
        onDeviceOnlyDeleted = { showDeviceOnlyKeptSnackbar() },
        restoreEnabled = { LicenseEntitlements.restoreEnabled(this) },
        onRestore = { records -> startRestore(records) },
        onRerun = { record -> newAnalysis.rerun(record) },
        onDeleteQueued = { workId, items ->
            viewModel.hideQueuedDeletes(items.filter { it.mode == SessionDeletes.Mode.EVERYWHERE }.map { it.localId })
            deleteFeedback.queued(workId, items.size)
            refresh(reconcile = false)
        },
    )

    /** Cold start / return with an already-full quota → persistent support screen. */
    private fun openLimitScreenIfFull() {
        lifecycleScope.launch {
            val localCount = withContext(Dispatchers.IO) {
                SessionStore.list(this@HomeActivity).size
            }
            AccountCache.refreshSessionLimit(this@HomeActivity, localCount)
            quotaCard.openLimitScreenIfReached(localCount)
        }
    }

    override fun onStart() {
        super.onStart()
        // A sign-out finished with no screen left to route, and Android
        // refused the background start to sign-in: route from here.
        if (SignOutRun.claimUnclaimed()) AuthRoute.toSignIn(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        openUpdateGateOnce()
    }

    /**
     * A6.1, on entry: a build the backend no longer supports opens its gate
     * once a process (the minimum can arrive with the background status
     * check, after Home is up, so every resume asks). Back on Home, the list,
     * the viewer and exports all still work.
     */
    private fun openUpdateGateOnce() {
        if (UpdateRequiredActivity.shownThisProcess || AppPolicy.newAnalysisAllowed(this)) return
        AnalysisNavHelper.openUpdateRequired(this)
    }

    /** Below the recommended version: one snackbar per installed version and recommendation. */
    private fun nudgeIfUpdateRecommended() {
        val verdict = AppPolicy.version(this) as? VersionVerdict.Recommended ?: return
        if (!verdict.nudge) return
        AppPolicy.markNudged(this, verdict)
        Snackbar.make(binding.homeRoot, R.string.update_recommended, Snackbar.LENGTH_LONG)
            .setAction(R.string.update_action) { UpdateRequiredActivity.openStore(this) }
            .show()
    }

    /**
     * @param deep verify blobs really exist in Drive (pull-to-refresh) rather
     *   than trusting the backend index (cheap resume check).
     * @param reconcile false only re-reads the phone's list, for a change made
     *   here that the cloud check has nothing to add to.
     */
    private fun refresh(deep: Boolean = false, reconcile: Boolean = true) {
        viewModel.refreshList()
        if (reconcile) cloudCheck.request(deep)
    }

    /** A list read from [HomeViewModel.refreshList], with which rows have no frames here. */
    private fun showList(list: HomeViewModel.HomeList) {
        adapter.submit(list.sessions, list.withoutLocalData)
        // Demo: analyses are recorded silently and there is no restore, so
        // the list carries no sync badge, bar or "only in cloud" state.
        adapter.setSyncVisible(showsCloudState())
        binding.emptyState.isVisible = list.sessions.isEmpty()
        updateCloudBackups()
        quotaCard.render(list.sessions.size)
        // A refresh can drop rows out from under a selection.
        selection.updateSelectionBar()
        // No quota count stored from here: these rows leave out the ones a
        // queued delete is about to remove, and the quota counts every row
        // the index holds, which SessionStore stores with each write.
    }

    /**
     * Whether this account sees any cloud state at all. Demo accounts record
     * analyses silently and have no restore, so every badge, bar, toast and
     * download offer tied to backup is withheld rather than shown greyed out.
     */
    private fun showsCloudState(): Boolean = LicenseEntitlements.cloudBackupEnabled(this)

    // ── Row actions ──────────────────────────────────────────────────────

    private fun openSession(record: SessionRecord) {
        val hasLocal = adapter.hasLocalData(record.id)
        when {
            hasLocal -> startActivity(SessionOpenHelper.intentFor(this, record))
            // A demo account cannot pull its recorded copy back, so a row with
            // no local data is simply unopenable — no download offer.
            record.isRestorable(hasLocal) && LicenseEntitlements.restoreEnabled(this) -> Dialogs.confirm(
                this,
                getString(R.string.restore_analysis_title_fmt, record.name),
                getText(R.string.restore_analysis_body),
                R.string.restore_action,
            ) { startRestore(listOf(record)) }
            else -> SessionOpenHelper.openOrExplain(this, record, hasLocal)
        }
    }

    /**
     * Queue background restores and stay on Home. Row progress comes from
     * [HomeTransferWatch] (same badge/bar as uploads) so the list stays
     * interactive — no blocking "Downloading…" dialog. The rows' cloud lookup
     * and the queueing run in [HomeViewModel], through the same
     * `RestoreStart.request` Settings uses, so a rotation does not lose them.
     */
    private fun startRestore(records: List<SessionRecord>) {
        if (records.isEmpty() || !RestoreRequest.allowedOrSay(this)) return
        selection.clearSelection()
        viewModel.restoreRows(records)
    }

    /** What a restore request came to, in one toast; the list re-reads for the rows it wrote. */
    private fun showRestoreOutcome(outcome: RestoreRequest) {
        when (outcome) {
            RestoreRequest.Paused -> RestoreRequest.sayPaused(this)
            is RestoreRequest.Queued -> {
                refresh(reconcile = false)
                val counts = outcome.counts
                val summary = RestoreSummary.of(resources, outcome.total, counts.started, counts.alreadyRunning)
                Feedback.toast(this, summary.text, long = summary.failed)
            }
        }
    }

    /** Offer the backups this phone has no row for ([HomeViewModel.offeredBackups]). */
    private fun updateCloudBackups() {
        cloudBackups.refresh(lifecycleScope, read = viewModel::offeredBackups) { offered ->
            val emptyTitle = if (offered.isEmpty()) R.string.home_empty_title else R.string.home_empty_title_cloud
            binding.tvEmptyTitle.setText(emptyTitle)
        }
    }

    private fun hideCloudBackups(backups: List<CloudBackupListing.Backup>) {
        CloudBackupListing.hide(this, backups.map { it.cloudId })
        updateCloudBackups()
        Feedback.toast(this, R.string.cloud_backups_hidden, long = true)
    }

    private fun showDeviceOnlyKeptSnackbar() {
        CrispToast.show(this, getString(R.string.delete_device_only_done), long = true)
    }

    override fun onDestroy() {
        if (::adapter.isInitialized) adapter.clearThumbCache()
        super.onDestroy()
    }
}
