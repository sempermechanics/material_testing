package com.indicvision.semper.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.indicvision.semper.R

/**
 * Rows for the settings page's per-analysis list. Which buttons a row shows is
 * decided by [AnalysisEntry]; what they do is the host's business, so every
 * action is a callback.
 */
@Suppress("LongParameterList") // one adapter, one callback per row action
class AnalysisDataAdapter(
    private val stateLine: (AnalysisEntry) -> String,
    private val backupLabel: (AnalysisEntry) -> Int?,
    private val onOpen: (AnalysisEntry) -> Unit,
    private val onBackup: (AnalysisEntry) -> Unit,
    private val onLocalDownload: (AnalysisEntry) -> Unit,
    private val onCloudRestore: (AnalysisEntry) -> Unit,
    private val onDelete: (AnalysisEntry, View) -> Unit,
) : RecyclerView.Adapter<AnalysisDataAdapter.Row>() {

    private val entries = mutableListOf<AnalysisEntry>()
    private var downloadingKeys: Set<String> = emptySet()

    fun submit(items: List<AnalysisEntry>) {
        entries.clear()
        entries.addAll(items)
        @Suppress("NotifyDataSetChanged") // whole-list refresh after a cloud round-trip
        notifyDataSetChanged()
    }

    /** Keys from [AnalysisEntry.downloadKey] with an in-flight Download / restore. */
    fun setDownloadingKeys(keys: Set<String>) {
        if (keys == downloadingKeys) return
        downloadingKeys = keys
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    /** Drops one row immediately, before its deletion is actually sent. */
    fun removeAt(position: Int) {
        if (position !in entries.indices) return
        entries.removeAt(position)
        notifyItemRemoved(position)
    }

    override fun getItemCount(): Int = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row = Row(
        LayoutInflater.from(parent.context).inflate(R.layout.item_analysis_data, parent, false),
    )

    override fun onBindViewHolder(holder: Row, position: Int) = holder.bind(entries[position])

    inner class Row(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.tvAnalysisName)
        private val state: TextView = view.findViewById(R.id.tvAnalysisState)
        private val backup: MaterialButton = view.findViewById(R.id.btnAnalysisBackup)
        private val localDownload: ImageButton = view.findViewById(R.id.btnAnalysisLocalDownload)
        private val restore: ImageButton = view.findViewById(R.id.btnAnalysisRestore)
        private val delete: ImageButton = view.findViewById(R.id.btnAnalysisDelete)

        fun bind(entry: AnalysisEntry) {
            name.text = entry.name
            val busy = entry.downloadKey() in downloadingKeys
            state.text = if (busy) {
                itemView.context.getString(R.string.download_analysis_working)
            } else {
                stateLine(entry)
            }

            val hasCloud = entry.offersCloudActions()
            val showDownload = entry.offersDownload()
            val showRestore = entry.offersRestore()
            // Download when cloud is listed; Restore only when local frames are missing.
            localDownload.isVisible = showDownload
            restore.isVisible = showRestore
            delete.isVisible = hasCloud
            localDownload.isEnabled = !busy
            restore.isEnabled = !busy
            localDownload.alpha = if (busy) BUSY_ICON_ALPHA else 1f
            restore.alpha = if (busy) BUSY_ICON_ALPHA else 1f
            localDownload.setOnClickListener {
                if (entry.downloadKey() in downloadingKeys) return@setOnClickListener
                onLocalDownload(entry)
            }
            restore.setOnClickListener {
                if (entry.downloadKey() in downloadingKeys) return@setOnClickListener
                onCloudRestore(entry)
            }
            delete.setOnClickListener { onDelete(entry, itemView) }

            // A backup action only applies to a row with no cloud copy listed.
            val label = if (hasCloud) null else backupLabel(entry)
            backup.isVisible = label != null && !busy
            label?.let {
                backup.setText(it)
                backup.setOnClickListener { onBackup(entry) }
            }

            itemView.isClickable = entry.record != null
            itemView.setOnClickListener(if (entry.record != null) View.OnClickListener { onOpen(entry) } else null)
        }
    }

    private companion object {
        /** Dim action icons while a transfer for this row is running. */
        const val BUSY_ICON_ALPHA = 0.4f
    }
}
