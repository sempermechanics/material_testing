package com.indicvision.semper.ui.common

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.indicvision.semper.R

/**
 * Non-modal transfer strip: one progress page at a time, with left/right
 * navigation when multiple transfers are active. Does not block the host UI.
 */
class TransferBannerController(
    private val root: View,
) {

    data class Transfer(
        val id: String,
        val title: String,
        val percent: Int = 0,
        val status: String = "",
        val cancellable: Boolean = true,
        val onCancel: (() -> Unit)? = null,
    )

    private val titleView: TextView = root.findViewById(R.id.tvTransferTitle)
    private val statusView: TextView = root.findViewById(R.id.tvTransferStatus)
    private val progress: LinearProgressIndicator = root.findViewById(R.id.transferProgress)
    private val pageView: TextView = root.findViewById(R.id.tvTransferPage)
    private val btnPrev: ImageButton = root.findViewById(R.id.btnTransferPrev)
    private val btnNext: ImageButton = root.findViewById(R.id.btnTransferNext)
    private val btnCancel: TextView = root.findViewById(R.id.btnTransferCancel)

    private val transfers = linkedMapOf<String, Transfer>()
    private var pageIndex = 0

    init {
        btnPrev.setOnClickListener { moveBy(-1) }
        btnNext.setOnClickListener { moveBy(1) }
        btnCancel.setOnClickListener {
            current()?.onCancel?.invoke()
        }
        render()
    }

    fun upsert(transfer: Transfer) {
        val isNew = transfer.id !in transfers
        transfers[transfer.id] = transfer
        if (isNew) pageIndex = transfers.size - 1
        render()
    }

    fun updateProgress(id: String, percent: Int, status: String? = null) {
        val existing = transfers[id] ?: return
        transfers[id] = existing.copy(
            percent = percent.coerceIn(0, 100),
            status = status ?: existing.status,
        )
        render()
    }

    fun remove(id: String) {
        if (transfers.remove(id) == null) return
        if (pageIndex >= transfers.size) pageIndex = (transfers.size - 1).coerceAtLeast(0)
        render()
    }

    fun contains(id: String): Boolean = id in transfers

    fun size(): Int = transfers.size

    private fun current(): Transfer? = transfers.values.elementAtOrNull(pageIndex)

    private fun moveBy(delta: Int) {
        if (transfers.size <= 1) return
        pageIndex = (pageIndex + delta + transfers.size) % transfers.size
        render()
    }

    private fun render() {
        val item = current()
        if (item == null) {
            root.isVisible = false
            return
        }
        root.isVisible = true
        titleView.text = item.title
        val multi = transfers.size > 1
        btnPrev.isVisible = multi
        btnNext.isVisible = multi
        pageView.isVisible = multi
        if (multi) {
            pageView.text = root.context.getString(
                R.string.transfer_banner_page,
                pageIndex + 1,
                transfers.size,
            )
        }
        btnCancel.isVisible = item.cancellable && item.onCancel != null
        if (item.percent <= 0) {
            progress.isIndeterminate = true
            statusView.text = item.status.ifBlank {
                root.context.getString(R.string.transfer_banner_working)
            }
        } else {
            progress.isIndeterminate = false
            progress.setProgressCompat(item.percent, true)
            statusView.text = item.status.ifBlank {
                root.context.getString(R.string.transfer_banner_percent, item.percent)
            }
        }
    }
}
