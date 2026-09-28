package com.indicvision.semper.ui.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.indicvision.semper.R
import com.indicvision.semper.data.TypedLoads
import com.indicvision.semper.ui.common.Insets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Bending without a machine: the student types the mass on the hanger for
 * each deformed photo, in kg, one box per frame. The keypad's Next walks down
 * the list, scrolling it above the keyboard.
 *
 * Every box needs a number: a photo with no weight on the hanger is 0, never
 * an empty box. **Done** refuses while any box is empty or not a number and
 * puts the cursor there. Closing the sheet any other way keeps what was
 * typed (a box that is not a number keeps its old value: back must never
 * lose a column of loads); the wizard then waits at Next until every photo
 * has its load ([AnalysisViewModel.typedLoadsMissing]).
 */
internal object TypedLoadsSheet {

    /** One deformed frame as the sheet lists it. */
    class Frame(val path: String, val label: String, val detail: String)

    /**
     * The sheet for the wizard's current deformed frames, prefilled with what
     * is typed; Done / back store the masses on [viewModel], then [onSaved].
     */
    fun showFor(activity: AppCompatActivity, viewModel: AnalysisViewModel, onSaved: () -> Unit) {
        val paths = viewModel.defFilePaths
        val video = viewModel.defFromVideo
        val labelRes = if (video) R.string.load_typed_frame_fmt else R.string.load_typed_photo_fmt
        val frames = paths.mapIndexed { index, path ->
            Frame(
                path = path,
                label = activity.getString(labelRes, index + 1),
                detail = if (video) {
                    viewModel.defFrameTimesMs.getOrNull(index)?.let(VideoFrameExtractor::formatClock).orEmpty()
                } else {
                    viewModel.defOriginalNames.getOrNull(index).orEmpty()
                },
            )
        }
        val initial = viewModel.typedLoadsKg.takeIf { it.size == paths.size } ?: List(paths.size) { null }
        show(activity, frames, initial) { kg ->
            viewModel.setTypedLoads(kg)
            onSaved()
        }
    }

    fun show(
        activity: AppCompatActivity,
        frames: List<Frame>,
        initialKg: List<Float?>,
        onDone: (List<Float?>) -> Unit,
    ) {
        if (frames.isEmpty()) return
        val root = activity.layoutInflater.inflate(R.layout.sheet_typed_loads, null)
        val list = root.findViewById<RecyclerView>(R.id.rvTypedLoads)
        val adapter = RowAdapter(activity, frames, initialKg, list)
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.setItemViewCacheSize(frames.size.coerceAtMost(MAX_CACHED_ROWS))
        Insets.padImeBottom(list)

        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(root)
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.isDraggable = false
        sheet.setCanceledOnTouchOutside(false)

        var committed = false
        fun commit(result: List<Float?>) {
            if (committed) return
            committed = true
            onDone(result)
        }
        root.findViewById<View>(R.id.btnTypedDone).setOnClickListener {
            val unfilled = adapter.firstUnfilled()
            if (unfilled >= 0) {
                adapter.focusRow(unfilled, showError = true)
            } else {
                commit(adapter.values(keepOldWhenInvalid = false))
                sheet.dismiss()
            }
        }
        sheet.setOnCancelListener { commit(adapter.values(keepOldWhenInvalid = true)) }
        sheet.setOnShowListener {
            val bottom = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottom != null) {
                bottom.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                bottom.requestLayout()
                BottomSheetBehavior.from(bottom).state = BottomSheetBehavior.STATE_EXPANDED
            }
            list.post { adapter.focusRow(adapter.firstBlank(), showError = false) }
        }
        sheet.show()
    }

    /**
     * Rows hold their text in [texts], not in the views, so recycling a row
     * never loses or moves what was typed.
     */
    private class RowAdapter(
        private val activity: AppCompatActivity,
        private val frames: List<Frame>,
        private val initialKg: List<Float?>,
        private val list: RecyclerView,
    ) : RecyclerView.Adapter<RowAdapter.Holder>() {

        private val texts: Array<String> = Array(frames.size) { formatKg(initialKg.getOrNull(it)) }
        private var errorRow = -1
        private val thumbs = HashMap<String, Bitmap>()

        override fun getItemCount(): Int = frames.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.row_typed_load, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position)

        fun values(keepOldWhenInvalid: Boolean): List<Float?> = texts.mapIndexed { index, text ->
            when (val parsed = TypedLoads.parseKg(text)) {
                TypedLoads.Parsed.Blank -> null
                is TypedLoads.Parsed.Kg -> parsed.kg
                TypedLoads.Parsed.Invalid -> if (keepOldWhenInvalid) initialKg.getOrNull(index) else null
            }
        }

        /** The first box that is empty or not a number, or -1 when every photo has its mass. */
        fun firstUnfilled(): Int = texts.indexOfFirst { TypedLoads.parseKg(it) !is TypedLoads.Parsed.Kg }

        fun firstBlank(): Int = texts.indexOfFirst { it.isBlank() }.takeIf { it >= 0 } ?: 0

        /** Scrolls [row] into view and puts the cursor in its box. */
        fun focusRow(row: Int, showError: Boolean) {
            if (row !in texts.indices) return
            if (showError) {
                val old = errorRow
                errorRow = row
                if (old >= 0) notifyItemChanged(old)
                notifyItemChanged(row)
            }
            list.scrollToPosition(row)
            list.post {
                val holder = list.findViewHolderForAdapterPosition(row) as? Holder ?: return@post
                holder.box.requestFocus()
                holder.box.setSelection(holder.box.text.length)
                showKeyboard(holder.box)
            }
        }

        private fun showKeyboard(box: EditText) {
            activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(box, 0)
        }

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val thumb: ImageView = itemView.findViewById(R.id.ivTypedThumb)
            private val label: TextView = itemView.findViewById(R.id.tvTypedLabel)
            private val detail: TextView = itemView.findViewById(R.id.tvTypedSub)
            val box: EditText = itemView.findViewById(R.id.etTypedKg)
            private var binding = false

            init {
                box.doAfterTextChanged { text ->
                    val row = bindingAdapterPosition
                    if (binding || row == RecyclerView.NO_POSITION) return@doAfterTextChanged
                    texts[row] = text?.toString().orEmpty()
                    if (row == errorRow) box.error = null
                }
                box.setOnEditorActionListener { _, actionId, _ ->
                    val row = bindingAdapterPosition
                    if (actionId == EditorInfo.IME_ACTION_NEXT && row != RecyclerView.NO_POSITION) {
                        focusRow(row + 1, showError = false)
                        true
                    } else {
                        false
                    }
                }
            }

            fun bind(position: Int) {
                val frame = frames[position]
                binding = true
                label.text = frame.label
                detail.text = frame.detail
                detail.visibility = if (frame.detail.isEmpty()) View.GONE else View.VISIBLE
                box.setText(texts[position])
                val last = position == frames.lastIndex
                val action = if (last) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                box.imeOptions = action or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                box.error = if (position == errorRow) activity.getString(errorRes(texts[position])) else null
                box.contentDescription = activity.getString(R.string.load_typed_box_desc, frame.label)
                binding = false
                bindThumb(frame.path)
            }

            private fun errorRes(text: String): Int =
                if (text.isBlank()) R.string.load_typed_blank else R.string.load_typed_invalid

            private fun bindThumb(path: String) {
                thumb.tag = path
                val cached = thumbs[path]
                if (cached != null) {
                    thumb.setImageBitmap(cached)
                    return
                }
                thumb.setImageDrawable(null)
                activity.lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { decodeThumb(path) } ?: return@launch
                    thumbs[path] = bmp
                    if (thumb.tag == path) thumb.setImageBitmap(bmp)
                }
            }
        }
    }

    /** A small bitmap of the frame; null for a file that is gone or not an image. */
    @WorkerThread
    private fun decodeThumb(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (File(path).isFile) BitmapFactory.decodeFile(path, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (longEdge / sample > THUMB_MAX_EDGE_PX) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** A stored mass as the box shows it: no trailing zeros, blank for none. */
    fun formatKg(kg: Float?): String {
        if (kg == null) return ""
        return String.format(Locale.US, "%.3f", kg).trimEnd('0').trimEnd('.')
    }

    /** Rows are small; keep them all bound so Next never lands on an unbound row. */
    private const val MAX_CACHED_ROWS = 60
    private const val THUMB_MAX_EDGE_PX = 160
}
