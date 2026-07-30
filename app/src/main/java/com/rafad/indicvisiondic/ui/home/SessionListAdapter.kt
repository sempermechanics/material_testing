// List adapter: onBindViewHolder assembles one row's subtitle/badges inline, and
// literal view-type/dimension constants read clearest there too.
@file:Suppress("MagicNumber", "CyclomaticComplexMethod")

package com.rafad.indicvisiondic.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.SessionRecord
import com.rafad.indicvisiondic.ui.analysis.EngineFailure
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home session list. Selection state lives with the owner ([isSelected]); this
 * adapter only paints rows and forwards clicks.
 */
class SessionListAdapter(
    private val isSelected: (String) -> Boolean,
    private val onClick: (SessionRecord) -> Unit,
    private val onLongClick: (SessionRecord) -> Unit,
    private val onBadgeClick: (SessionRecord) -> Unit = {},
) : RecyclerView.Adapter<SessionListAdapter.Holder>() {

    private var items: List<SessionRecord> = emptyList()
    private val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())

    /** Path → thumbnail; recycles evicted bitmaps. Cap keeps scroll GC mild. */
    private val thumbCache =
        object : LinkedHashMap<String, Bitmap>(THUMB_CACHE_MAX + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
                if (size <= THUMB_CACHE_MAX) return false
                eldest?.value?.takeIf { !it.isRecycled }?.recycle()
                return true
            }
        }

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

    /** Rows whose ids are in [ids], in list order. */
    fun recordsFor(ids: Collection<String>): List<SessionRecord> =
        items.filter { it.id in ids }

    /** Drop cached thumbs (e.g. when Home is destroyed). */
    fun clearThumbCache() {
        for (bmp in thumbCache.values) {
            if (!bmp.isRecycled) bmp.recycle()
        }
        thumbCache.clear()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val card: com.google.android.material.card.MaterialCardView =
            v.findViewById(R.id.sessionCard)
        val thumb: ImageView = v.findViewById(R.id.sessionThumb)
        val check: ImageView = v.findViewById(R.id.sessionCheck)
        val title: TextView = v.findViewById(R.id.sessionTitle)
        val subtitle: TextView = v.findViewById(R.id.sessionSubtitle)
        val badge: TextView = v.findViewById(R.id.sessionBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = items.size

    /**
     * Date · size · headline, plus why it stopped when it did.
     *
     * A run cut short reads as "39 of 50 frames" rather than "39 frames": the
     * count alone cannot distinguish a short run from a shorter test.
     */
    private fun subtitleFor(ctx: android.content.Context, r: SessionRecord): String = buildString {
        append(dateFmt.format(Date(r.createdAt)))
        append(" · ")
        if (r.isSweep) {
            append(ctx.getString(R.string.session_sweep_kind))
        } else if (r.stoppedEarly && r.plannedFrameCount > r.frameCount) {
            append(
                ctx.resources.getQuantityString(
                    R.plurals.session_frames_of_fmt,
                    r.plannedFrameCount,
                    r.frameCount,
                    r.plannedFrameCount,
                ),
            )
        } else {
            append(ctx.getString(R.string.session_frames_fmt, r.frameCount))
        }
        if (r.headline.isNotBlank()) {
            append(" · ")
            append(r.headline)
        }
        if (r.stoppedEarly) {
            append(" · ")
            append(ctx.getString(EngineFailure.shortReasonRes(r.stopCode)))
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = items[position]
        val ctx = holder.itemView.context
        holder.title.text = r.name
        holder.subtitle.text = subtitleFor(ctx, r)
        holder.badge.text = when (r.syncState) {
            SessionRecord.SyncState.SYNCED -> ctx.getString(R.string.badge_synced)
            SessionRecord.SyncState.PENDING -> ctx.getString(R.string.badge_pending)
            SessionRecord.SyncState.LOCAL_ONLY -> ctx.getString(R.string.badge_local)
            SessionRecord.SyncState.FAILED -> ctx.getString(R.string.badge_not_backed_up)
        }
        holder.badge.setTextColor(
            if (r.syncState == SessionRecord.SyncState.FAILED) {
                ctx.getColor(R.color.semantic_danger)
            } else {
                ctx.getColor(R.color.sky_on_container)
            },
        )
        holder.badge.setOnClickListener { onBadgeClick(r) }

        val refFile = File(r.refPath)
        if (refFile.exists()) {
            val cached = thumbCache[r.refPath]
            val bmp = if (cached != null && !cached.isRecycled) {
                cached
            } else {
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                BitmapFactory.decodeFile(r.refPath, opts)?.also { thumbCache[r.refPath] = it }
            }
            holder.thumb.setImageBitmap(bmp)
        } else {
            holder.thumb.setImageDrawable(null)
        }

        val selected = isSelected(r.id)
        holder.check.isVisible = selected
        holder.card.setCardBackgroundColor(
            ctx.getColor(if (selected) R.color.sky_container else R.color.surface_muted),
        )
        holder.card.strokeColor =
            ctx.getColor(if (selected) R.color.sky_primary else R.color.surface_outline)

        // Outside selection mode a tap opens the analysis and a long-press
        // starts selecting; inside it, every tap just toggles a row. That
        // policy lives in the callbacks Home wires.
        holder.itemView.setOnClickListener { onClick(r) }
        holder.itemView.setOnLongClickListener {
            onLongClick(r)
            true
        }
    }

    companion object {
        private const val THUMB_CACHE_MAX = 24
    }
}
