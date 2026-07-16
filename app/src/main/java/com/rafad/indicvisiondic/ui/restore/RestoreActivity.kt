package com.rafad.indicvisiondic.ui.restore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.CloudRestore
import com.rafad.indicvisiondic.data.net.CloudSessionDto
import com.rafad.indicvisiondic.ui.Insets
import kotlinx.coroutines.launch

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
    private val adapter = BackupAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_restore)
        Insets.padVertical(findViewById(R.id.restoreRoot))

        rv = findViewById(R.id.rvBackups)
        tvEmpty = findViewById(R.id.tvRestoreEmpty)
        tvStatus = findViewById(R.id.tvRestoreStatus)
        progress = findViewById(R.id.progressRestore)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        load()
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

    private fun restore(item: CloudSessionDto) {
        setBusy(true)
        tvStatus.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                CloudRestore.restore(this@RestoreActivity, item.sessionId) { done, total ->
                    runOnUiThread {
                        tvStatus.text = getString(R.string.restore_progress_fmt, done, total)
                    }
                }
                Toast.makeText(
                    this@RestoreActivity,
                    getString(R.string.restore_done_fmt, item.specimen ?: item.sessionId),
                    Toast.LENGTH_LONG,
                ).show()
                finish() // Home reloads the list on resume
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Toast.makeText(
                    this@RestoreActivity,
                    getString(R.string.restore_failed_fmt, e.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                setBusy(false)
                tvStatus.visibility = View.GONE
            }
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
