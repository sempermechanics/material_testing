package com.sempermechanics.semper.ui.analysis.frames

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ActivityStaticAnalysisBinding
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.common.dialog.Feedback
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The deformed card's frame-order strip: a sort menu (name, date, either
 * direction) and drag-to-reorder in manual mode. [onReordered] redraws the
 * deformed slot once a sort lands; frame sizes are re-checked after every
 * reorder.
 */
class FrameOrderController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    binding: ActivityStaticAnalysisBinding,
    private val onReordered: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The strip's adapter; the deformed slot submits the frames to it. */
    val adapter = FrameOrderAdapter { orderedPaths -> applyManualOrder(orderedPaths) }

    init {
        binding.rvFrameOrder.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        binding.rvFrameOrder.adapter = adapter
        FrameOrderAdapter.attachDrag(binding.rvFrameOrder, adapter)
        binding.btnFrameOrderSort.setOnClickListener { anchor ->
            AnalysisFrameOrderMenuHelper.show(
                activity = activity,
                anchor = anchor,
                mode = viewModel.inputs.value.defOrderMode,
                direction = viewModel.inputs.value.defOrderDirection,
                onSelect = ::applyMode,
            )
        }
    }

    /** Stops the strip's thumbnail loads: the screen is going. */
    fun release() = adapter.release()

    internal fun applyMode(mode: FrameOrderMode, direction: FrameOrderDirection) {
        if (viewModel.inputs.value.defFromVideo || viewModel.defFilePaths.size <= 1) return
        viewModel.edit { it.copy(defOrderMode = mode, defOrderDirection = direction) }
        adapter.dragEnabled = mode == FrameOrderMode.MANUAL
        if (mode == FrameOrderMode.MANUAL) {
            Feedback.toast(activity, R.string.frame_order_manual_hint)
            return
        }
        // Import no longer probes URI dates (kept the overlay at 0% on PLC).
        // Resolve from the cached files the first time the user sorts by date.
        val snapshot = viewModel.inputs.value.deformedFrames
        activity.lifecycleScope.launch {
            val dated = withContext(io) {
                if (mode == FrameOrderMode.DATE && snapshot.all { it.date == DeformedFrame.UNKNOWN_DATE }) {
                    snapshot.map { it.copy(date = FrameOrderHelper.resolveDateMs(File(it.path))) }
                } else {
                    snapshot
                }
            }
            val ordered = withContext(Dispatchers.Default) { FrameOrderHelper.reorder(dated, mode, direction) }
            val staged = withContext(io) { FrameOrderHelper.reprefixTempFiles(ordered) }
            viewModel.edit { it.copy(deformedFrames = staged) }
            onReordered()
            viewModel.checkFrameSizes(activity.resources)
        }
    }

    internal fun applyManualOrder(orderedPaths: List<String>) {
        if (orderedPaths == viewModel.defFilePaths) return
        val indexOf = viewModel.defFilePaths.withIndex().associate { it.value to it.index }
        val order = orderedPaths.mapNotNull { indexOf[it] }
        if (order.size != orderedPaths.size) return
        // Keep file names as-is during drag; analysis uses list order, not path sort.
        val current = viewModel.inputs.value.deformedFrames
        val reordered = FrameOrderHelper.reorder(current, FrameOrderMode.MANUAL, manualOrder = order)
        viewModel.edit { it.copy(deformedFrames = reordered, defOrderMode = FrameOrderMode.MANUAL) }
        viewModel.checkFrameSizes(activity.resources)
    }
}
