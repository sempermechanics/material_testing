package com.rafad.indicvisiondic.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.rafad.indicvisiondic.R

/**
 * Rows for the settings page's per-analysis list. Which buttons a row shows is
 * decided by [AnalysisEntry.location]; what they do is the host's business, so
 * every action is a callback.
 */
class AnalysisDataAdapter(
    private val stateLine: (AnalysisEntry) -> String,
    private val backupLabel: (AnalysisEntry) -> Int?,
    private val onOpen: (AnalysisEntry) -> Unit,
    private val onBackup: (AnalysisEntry) -> Unit,
    private val onRestore: (AnalysisEntry) -> Unit,
    private val onDelete: (AnalysisEntry, View) -> Unit,
) : RecyclerView.Adapter<AnalysisDataAdapter.Row>() {

    private val entries = mutableListOf<AnalysisEntry>()

    fun submit(items: List<AnalysisEntry>) {
        entries.clear()
        entries.addAll(items)
        @Suppress("NotifyDataSetChanged") // whole-list refresh after a cloud round-trip
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
        private val restore: ImageButton = view.findViewById(R.id.btnAnalysisRestore)
        private val delete: ImageButton = view.findViewById(R.id.btnAnalysisDelete)

        fun bind(entry: AnalysisEntry) {
            name.text = entry.name
            state.text = stateLine(entry)

            val hasCloud = entry.cloud != null
            restore.isVisible = hasCloud
            delete.isVisible = hasCloud
            restore.setOnClickListener { onRestore(entry) }
            delete.setOnClickListener { onDelete(entry, itemView) }

            // A backup action only applies to a row with no cloud copy listed.
            val label = if (hasCloud) null else backupLabel(entry)
            backup.isVisible = label != null
            label?.let {
                backup.setText(it)
                backup.setOnClickListener { onBackup(entry) }
            }

            itemView.isClickable = entry.record != null
            itemView.setOnClickListener(if (entry.record != null) View.OnClickListener { onOpen(entry) } else null)
        }
    }
}
