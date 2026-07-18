package com.rafad.indicvisiondic.ui.restore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.CloudRestore
import com.rafad.indicvisiondic.data.DicRestoreWorker
import com.rafad.indicvisiondic.data.SessionStore
import com.rafad.indicvisiondic.data.net.CloudSessionDto
import com.rafad.indicvisiondic.ui.common.Insets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Restore from cloud": lists the account's completed cloud analyses that
 * aren't on this device and rebuilds one locally on demand.
 *
 * Because the engine's .dat results are backed up too, a restored analysis
 * reopens fully in the results viewer — no re-run needed.
 */
class RestoreActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressSection: View
    private lateinit var progressBar: ProgressBar
    private val adapter = BackupAdapter()

    /** Restore outcomes already announced, so a replayed WorkInfo isn't re-toasted. */
    private val reportedOutcomes = mutableSetOf<java.util.UUID>()
    private var seededFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_restore)
        Insets.padVertical(findViewById(R.id.restoreRoot))

        rv = findViewById(R.id.rvBackups)
        tvEmpty = findViewById(R.id.tvRestoreEmpty)
        tvStatus = findViewById(R.id.tvRestoreStatus)
        progress = findViewById(R.id.progressRestore)
        progressSection = findViewById(R.id.restoreProgressSection)
        progressBar = findViewById(R.id.progressRestoreBar)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<View>(R.id.btnRestoreBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        load()
        observeRestores()
    }

    /**
     * Watch every restore job (by tag), so the progress bar re-appears whenever
     * this screen is opened while a restore is running — not just for one we
     * started this session. The work itself lives in WorkManager, so it keeps
     * going regardless of whether this screen is shown.
     */
    private fun observeRestores() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData("restore")
            .observe(this) { infos ->
                val active = infos.firstOrNull { !it.state.isFinished }
                if (active != null) {
                    val done = active.progress.getInt(DicRestoreWorker.KEY_DONE, 0)
                    val total = active.progress.getInt(DicRestoreWorker.KEY_TOTAL, 0)
                    showProgress(done, total, indeterminate = active.state != WorkInfo.State.RUNNING || total == 0)
                } else if (progressSection.isVisible) {
                    // A restore just finished — refresh the list so the
                    // now-local analysis drops off the "restorable" list.
                    progressSection.visibility = View.GONE
                    load()
                }

                val finished = infos.filter { it.state.isFinished }
                if (!seededFinished) {
                    // First emission: outcomes that predate this screen aren't
                    // news, and WorkManager replays them on every re-observe
                    // (including rotation). Remember them without announcing.
                    finished.forEach { reportedOutcomes.add(it.id) }
                    seededFinished = true
                } else {
                    finished.filter { reportedOutcomes.add(it.id) }.forEach { announceOutcome(it) }
                }
            }
    }

    /**
     * Say how a restore actually ended.
     *
     * Every terminal state used to collapse into "hide the bar and reload", so a
     * failed restore was indistinguishable from a successful one — the work
     * reports an error the UI simply dropped. Cancellation stays silent: the
     * user asked for it.
     */
    private fun announceOutcome(info: WorkInfo) {
        when (info.state) {
            WorkInfo.State.SUCCEEDED -> {
                val localId = info.outputData.getString(DicRestoreWorker.KEY_LOCAL_ID)
                lifecycleScope.launch {
                    val name = localId?.let {
                        withContext(Dispatchers.IO) { SessionStore.get(this@RestoreActivity, it)?.name }
                    }.orEmpty()
                    Toast.makeText(
                        this@RestoreActivity,
                        getString(R.string.restore_done_fmt, name),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }

            WorkInfo.State.FAILED -> {
                val reason = info.outputData.getString(DicRestoreWorker.KEY_ERROR).orEmpty()
                Toast.makeText(
                    this,
                    getString(R.string.restore_failed_fmt, reason),
                    Toast.LENGTH_LONG,
                ).show()
            }

            else -> Unit
        }
    }

    private fun load() {
        setBusy(true)
        lifecycleScope.launch {
            try {
                val backups = CloudRestore.listRestorable(this@RestoreActivity)
                adapter.submit(backups)
                tvEmpty.visibility = if (backups.isEmpty()) View.VISIBLE else View.GONE
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Toast.makeText(
                    this@RestoreActivity,
                    getString(R.string.restore_load_error, e.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                setBusy(false)
            }
        }
    }

    /**
     * Hand the download to WorkManager and watch it.
     *
     * The work is deliberately NOT owned by this Activity: restores are large,
     * and a lifecycleScope job dies the instant the user leaves the screen. Here
     * we only observe — leaving mid-restore is now harmless, and the analysis
     * simply shows up on Home when it lands.
     */
    private fun restore(item: CloudSessionDto) {
        CloudRestore.enqueueRestore(this, item.sessionId)
        // observeRestores() (watching by tag) shows and tracks the progress —
        // including if the user leaves and re-opens this screen mid-restore.
        showProgress(0, item.fileCount, indeterminate = true)
    }

    /** Show the pinned bottom progress bar. [indeterminate] while waiting for the network. */
    private fun showProgress(done: Int, total: Int, indeterminate: Boolean = false) {
        progressSection.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.restore_progress_fmt, done, total)
        progressBar.isIndeterminate = indeterminate
        if (!indeterminate && total > 0) {
            progressBar.max = total
            progressBar.setProgress(done, true)
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private inner class BackupAdapter : RecyclerView.Adapter<BackupAdapter.Holder>() {
        private var items: List<CloudSessionDto> = emptyList()

        fun submit(newItems: List<CloudSessionDto>) {
            items = newItems
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvBackupName)
            val sub: TextView = v.findViewById(R.id.tvBackupSub)
            val button: MaterialButton = v.findViewById(R.id.btnRestore)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_backup, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val s = items[position]
            holder.name.text = s.specimen ?: s.sessionId
            holder.sub.text = getString(R.string.restore_frames_fmt, s.fileCount, humanSize(s.totalBytes))
            holder.button.setOnClickListener { restore(s) }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
