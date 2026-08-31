@file:Suppress("MagicNumber")

package com.indicvision.semper.ui.common

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.indicvision.semper.R
import java.util.concurrent.Executors

/** Thumbnail grid for [MediaPickerSheet]. Selection lives on the sheet. */
class MediaGridAdapter(
    private val isSelected: (android.net.Uri) -> Boolean,
    private val onClick: (MediaStoreBrowser.Item) -> Unit,
) : RecyclerView.Adapter<MediaGridAdapter.Holder>() {

    private var items: List<MediaStoreBrowser.Item> = emptyList()
    private val thumbs = HashMap<Long, Bitmap>()
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun indexOf(uri: android.net.Uri): Int = items.indexOfFirst { it.uri == uri }

    fun submit(newItems: List<MediaStoreBrowser.Item>) {
        items = newItems
        @SuppressLint("NotifyDataSetChanged")
        fun notifyAllChanged() {
            notifyDataSetChanged()
        }
        notifyAllChanged()
    }

    fun shutdown() {
        executor.shutdownNow()
        thumbs.values.forEach { it.recycle() }
        thumbs.clear()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_tile, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.video.isVisible = item.isVideo
        holder.check.isVisible = isSelected(item.uri)
        holder.itemView.setOnClickListener { onClick(item) }
        val cached = thumbs[item.id]?.takeIf { !it.isRecycled }
        if (cached != null) {
            holder.thumb.setImageBitmap(cached)
            return
        }
        holder.thumb.setImageDrawable(null)
        holder.thumb.tag = item.id
        executor.execute {
            val bmp = loadThumb(holder.thumb, item) ?: return@execute
            main.post {
                if (holder.thumb.tag != item.id) {
                    bmp.recycle()
                    return@post
                }
                thumbs[item.id] = bmp
                holder.thumb.setImageBitmap(bmp)
            }
        }
    }

    private fun loadThumb(view: ImageView, item: MediaStoreBrowser.Item): Bitmap? {
        val resolver = view.context.contentResolver
        val size = Size(THUMB, THUMB)
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.loadThumbnail(item.uri, size, null)
            } else {
                @Suppress("DEPRECATION")
                if (item.isVideo) {
                    android.provider.MediaStore.Video.Thumbnails.getThumbnail(
                        resolver,
                        item.id,
                        android.provider.MediaStore.Video.Thumbnails.MINI_KIND,
                        null,
                    )
                } else {
                    android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                        resolver,
                        item.id,
                        android.provider.MediaStore.Images.Thumbnails.MINI_KIND,
                        null,
                    )
                }
            }
        }.getOrNull()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.imgMediaThumb)
        val video: ImageView = view.findViewById(R.id.imgMediaVideo)
        val check: ImageView = view.findViewById(R.id.imgMediaCheck)
    }

    companion object {
        private const val THUMB = 256
    }
}
