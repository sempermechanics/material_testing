@file:SuppressLint("NotifyDataSetChanged")

package com.sempermechanics.semper.ui.analysis.frames

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.WorkerThread
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ItemFrameOrderBinding
import com.sempermechanics.semper.ui.common.dp
import com.sempermechanics.semper.ui.common.media.ThumbnailLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Horizontal strip of deformed-frame thumbs with 1…N order badges.
 *
 * Thumbnails decode off the main thread through [thumbnails]; the host calls
 * [release] from `onDestroy`. Their keys ([FrameThumb]) need a stat of each
 * file, which runs on [statExecutor] when a list is submitted, never in a
 * bind on the main thread (readiness TE1-103).
 */
class FrameOrderAdapter(
    private val thumbnails: ThumbnailLoader<FrameThumb> = frameThumbnails(),
    private val statExecutor: Executor = Dispatchers.IO.asExecutor(),
    private val statKey: (String) -> FrameThumb = FrameThumb::of,
    private val onOrderChanged: (List<String>) -> Unit,
) : RecyclerView.Adapter<FrameOrderAdapter.Holder>() {

    private val paths = mutableListOf<String>()
    var dragEnabled: Boolean = false

    /** Each path's key from the latest stat that landed; a tile with none shows the placeholder. */
    private var keys: Map<String, FrameThumb> = emptyMap()

    /** Counts submits, so only the latest list's stat lands. */
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())

    /**
     * Shows [paths], then reads their keys on [statExecutor]. Until they land
     * a tile keeps the key its path had, so a resubmit does not flash the
     * placeholder; a path that now holds another file rebinds when they do.
     */
    fun submit(paths: List<String>) {
        this.paths.clear()
        this.paths.addAll(paths)
        notifyDataSetChanged()
        val submitted = ++generation
        val snapshot = paths.toList()
        statExecutor.execute {
            val fresh = snapshot.associateWith(statKey)
            main.post { if (submitted == generation) applyKeys(fresh) }
        }
    }

    private fun applyKeys(fresh: Map<String, FrameThumb>) {
        val old = keys
        keys = fresh
        paths.forEachIndexed { i, path -> if (old[path] != fresh[path]) notifyItemChanged(i, PAYLOAD_THUMB) }
    }

    fun currentPaths(): List<String> = paths.toList()

    override fun getItemCount(): Int = paths.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemFrameOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false), thumbnails)

    /** Recycles the decoded thumbnails; only once no tile can draw them again. */
    fun release() {
        thumbnails.clear()
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(keys[paths[position]], position + 1)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_THUMB }) {
            holder.setThumb(keys[paths[position]])
            return
        }
        if (payloads.contains(PAYLOAD_BADGE)) {
            holder.setBadge(position + 1)
            // The refresh follows a drop, and the change animation it triggers
            // cancels the drop's shrink mid-way; settle the tile here instead.
            holder.itemView.animate().cancel()
            applyDragging(holder.itemView, dragging = false, animate = false)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    /** Move within the list; do not notify range-changed mid-drag (breaks ItemTouchHelper). */
    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in paths.indices || to !in paths.indices) return
        val item = paths.removeAt(from)
        paths.add(to, item)
        notifyItemMoved(from, to)
    }

    fun refreshBadges() {
        if (paths.isEmpty()) return
        notifyItemRangeChanged(0, paths.size, PAYLOAD_BADGE)
    }

    class Holder(
        private val binding: ItemFrameOrderBinding,
        private val thumbnails: ThumbnailLoader<FrameThumb>,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun setBadge(order: Int) {
            binding.tvFrameBadge.text = String.format(Locale.US, "%d", order)
        }

        /** The tile's thumbnail; a null [key] (its stat has not landed) shows the placeholder. */
        fun setThumb(key: FrameThumb?) {
            thumbnails.bind(binding.imgFrameThumb, key)
        }

        fun bind(key: FrameThumb?, order: Int) {
            setBadge(order)
            applyDragging(itemView, false, animate = false)
            setThumb(key)
        }
    }

    /**
     * A tile's cache key. Reordering renames the staged files, so a path can
     * come back holding another frame (two picks with the same name); the
     * file's length and time tell them apart.
     */
    data class FrameThumb(val path: String, val length: Long, val modifiedMs: Long) {
        companion object {
            /** Stats [path]: off the main thread only ([submit] runs it on the stat executor). */
            @WorkerThread
            fun of(path: String): FrameThumb = File(path).let { FrameThumb(path, it.length(), it.lastModified()) }
        }
    }

    companion object {
        private const val PAYLOAD_BADGE = "badge"
        private const val PAYLOAD_THUMB = "thumb"

        /** Thumbnails are a strip of small tiles; decode no larger than they draw. */
        private const val THUMB_MAX_EDGE_PX = 160

        /** Tiles kept decoded; an evicted one is dropped, not recycled (it may still show). */
        private const val THUMBS_CACHED = 48

        /** The strip's loader: frames decode on the IO pool, and a missing or unreadable one shows the photos icon. */
        fun frameThumbnails(): ThumbnailLoader<FrameThumb> = ThumbnailLoader(
            maxCached = THUMBS_CACHED,
            executor = Dispatchers.IO.asExecutor(),
            decode = { decodeThumb(it.path) },
            placeholder = { it.setImageResource(R.drawable.ic_photos_share) },
        )

        /** The frame at [path], subsampled to the tile's size. */
        private fun decodeThumb(path: String): ThumbnailLoader.Decoded {
            if (!File(path).exists()) return ThumbnailLoader.Decoded.Missing
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longEdge / sample > THUMB_MAX_EDGE_PX) sample *= 2
            val bmp = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            } else {
                null
            }
            return bmp?.let { ThumbnailLoader.Decoded.Loaded(it) } ?: ThumbnailLoader.Decoded.Undecodable
        }
        private const val DRAG_SCALE = 1.06f
        private const val DRAG_ELEVATION_DP = 8f
        private const val DRAG_ANIM_MS = 120L

        fun applyDragging(view: View, dragging: Boolean, animate: Boolean = true) {
            val elevation = if (dragging) view.dp(DRAG_ELEVATION_DP) else 0f
            val scale = if (dragging) DRAG_SCALE else 1f
            ViewCompat.setElevation(view, elevation)
            if (animate) {
                view.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(DRAG_ANIM_MS)
                    .start()
            } else {
                view.scaleX = scale
                view.scaleY = scale
            }
        }

        fun attachDrag(recycler: RecyclerView, adapter: FrameOrderAdapter): ItemTouchHelper {
            val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                0,
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    if (!adapter.dragEnabled) return false
                    adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun isLongPressDragEnabled(): Boolean = adapter.dragEnabled

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                        applyDragging(viewHolder.itemView, dragging = true)
                        var p = recycler.parent
                        while (p != null) {
                            p.requestDisallowInterceptTouchEvent(true)
                            p = p.parent as? ViewGroup
                        }
                    }
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    applyDragging(viewHolder.itemView, dragging = false)
                    var p = recycler.parent
                    while (p != null) {
                        p.requestDisallowInterceptTouchEvent(false)
                        p = p.parent as? ViewGroup
                    }
                    adapter.refreshBadges()
                    adapter.onOrderChanged(adapter.currentPaths())
                }
            })
            helper.attachToRecyclerView(recycler)
            return helper
        }
    }
}
