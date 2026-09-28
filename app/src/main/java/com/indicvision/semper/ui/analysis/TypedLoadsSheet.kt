package com.indicvision.semper.ui.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
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
import com.indicvision.semper.data.TypedLoads.Entry
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
 * A dropdown picks how the boxes read ([Entry]): the total on the hanger, or
 * what was added since the photo before (negative: a weight taken off), with
 * each row's running total under it. Switching converts what is typed, both
 * ways without loss; the view model keeps totals either way.
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
        show(activity, frames, initial, viewModel.typedLoadsEntry) { kg, entry ->
            viewModel.typedLoadsEntry = entry
            viewModel.setTypedLoads(kg)
            onSaved()
        }
    }

    /** [initialKg] and what [onDone] gets are totals, whichever [Entry] the boxes were typed in. */
    fun show(
        activity: AppCompatActivity,
        frames: List<Frame>,
        initialKg: List<Float?>,
        initialEntry: Entry,
        onDone: (List<Float?>, Entry) -> Unit,
    ) {
        if (frames.isEmpty()) return
        val root = activity.layoutInflater.inflate(R.layout.sheet_typed_loads, null)
        val list = root.findViewById<RecyclerView>(R.id.rvTypedLoads)
        val adapter = RowAdapter(activity, frames, initialKg, initialEntry, list)
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.setItemViewCacheSize(frames.size.coerceAtMost(MAX_CACHED_ROWS))
        bindEntryDropdown(root, adapter)

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
            onDone(result, adapter.entry)
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

    /** The Absolute / Incremental dropdown, and the subtitle that explains the one picked. */
    private fun bindEntryDropdown(root: View, adapter: RowAdapter) {
        val context = root.context
        val spinner = root.findViewById<Spinner>(R.id.spTypedEntry)
        val subtitle = root.findViewById<TextView>(R.id.tvTypedSubtitle)
        val labels = ENTRIES.map { context.getString(it.second) }
        spinner.adapter = ArrayAdapter(context, R.layout.item_typed_entry, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        fun showSubtitle() {
            val incremental = adapter.entry == Entry.INCREMENTAL
            subtitle.setText(
                if (incremental) R.string.load_typed_subtitle_incremental else R.string.load_typed_subtitle,
            )
        }
        spinner.setSelection(ENTRIES.indexOfFirst { it.first == adapter.entry }, false)
        showSubtitle()
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val picked = ENTRIES[position].first
                if (picked == adapter.entry) return
                adapter.switchTo(picked)
                showSubtitle()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        root.findViewById<View>(R.id.typedEntryRow).setOnClickListener { spinner.performClick() }
    }

    /**
     * Rows hold their text in [texts], not in the views, so recycling a row
     * never loses or moves what was typed.
     */
    private class RowAdapter(
        private val activity: AppCompatActivity,
        private val frames: List<Frame>,
        private val initialKg: List<Float?>,
        initialEntry: Entry,
        private val list: RecyclerView,
    ) : RecyclerView.Adapter<RowAdapter.Holder>() {

        /** How [texts] read: totals, or what changed since the row before. */
        var entry: Entry = initialEntry
            private set

        private val texts: Array<String> = run {
            val shown = if (entry == Entry.INCREMENTAL) TypedLoads.increments(initialKg) else initialKg
            Array(frames.size) { formatKg(shown.getOrNull(it)) }
        }
        private var errorRow = -1
        private val keyboard = activity.getSystemService(InputMethodManager::class.java)
        private val thumbs = HashMap<String, Bitmap>()

        override fun getItemCount(): Int = frames.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.row_typed_load, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position)

        private fun boxes(): List<TypedLoads.Parsed> = texts.map { TypedLoads.parseKg(it, entry) }

        /** The totals, in kg; with [keepOldWhenInvalid] a bad box keeps the total it had. */
        fun values(keepOldWhenInvalid: Boolean): List<Float?> =
            TypedLoads.totals(boxes(), entry, if (keepOldWhenInvalid) initialKg else emptyList())

        /**
         * The first box that is empty, not a number, or takes the hanger below
         * 0 kg; -1 when every photo has its mass.
         */
        fun firstUnfilled(): Int = TypedLoads.totals(boxes(), entry).indexOfFirst { it == null }

        /**
         * Retypes every box for [next]: totals become increments or back. A box
         * that is blank or not a number is left as it is.
         */
        fun switchTo(next: Entry) {
            if (next == entry) return
            val boxes = boxes()
            val numbers = boxes.map { (it as? TypedLoads.Parsed.Kg)?.kg }
            val converted = if (next == Entry.INCREMENTAL) {
                TypedLoads.increments(numbers)
            } else {
                TypedLoads.runningTotals(numbers)
            }
            boxes.forEachIndexed { index, box ->
                if (box is TypedLoads.Parsed.Kg) texts[index] = formatKg(converted[index])
            }
            entry = next
            errorRow = -1
            // The keypad reopens for the new input type: increments need a minus key.
            list.findFocus()?.let { focused ->
                keyboard?.hideSoftInputFromWindow(focused.windowToken, 0)
                focused.clearFocus()
            }
            @Suppress("NotifyDataSetChanged") // every row's text, hint and keypad change
            notifyDataSetChanged()
            focusRow(firstBlank(), showError = false)
        }

        /** Each running total under its box, for the rows on screen; the rest get theirs when bound. */
        private fun refreshTotals() {
            if (entry != Entry.INCREMENTAL) return
            val totals = TypedLoads.totals(boxes(), entry)
            for (i in 0 until list.childCount) {
                val holder = list.getChildViewHolder(list.getChildAt(i)) as? Holder ?: continue
                val row = holder.bindingAdapterPosition
                if (row != RecyclerView.NO_POSITION) holder.showTotal(totals.getOrNull(row))
            }
        }

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

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val thumb: ImageView = itemView.findViewById(R.id.ivTypedThumb)
            private val label: TextView = itemView.findViewById(R.id.tvTypedLabel)
            private val detail: TextView = itemView.findViewById(R.id.tvTypedSub)
            private val total: TextView = itemView.findViewById(R.id.tvTypedTotal)
            val box: EditText = itemView.findViewById(R.id.etTypedKg)
            private var binding = false

            init {
                box.doAfterTextChanged { text ->
                    val row = bindingAdapterPosition
                    if (binding || row == RecyclerView.NO_POSITION) return@doAfterTextChanged
                    texts[row] = text?.toString().orEmpty()
                    if (row == errorRow) box.error = null
                    refreshTotals()
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
                val incremental = entry == Entry.INCREMENTAL
                val inputType = if (incremental) SIGNED_DECIMAL else UNSIGNED_DECIMAL
                if (box.inputType != inputType) {
                    // As the row's android:digits does: its own listener keeps the keypad's
                    // comma, which setInputType would replace with one that drops it.
                    box.keyListener = DigitsKeyListener.getInstance(if (incremental) "$DIGITS-" else DIGITS)
                    box.setRawInputType(inputType)
                }
                box.setText(texts[position])
                val last = position == frames.lastIndex
                val action = if (last) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                box.imeOptions = action or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                box.setHint(if (incremental) R.string.load_typed_hint_incremental else R.string.load_typed_hint)
                box.error = if (position == errorRow) activity.getString(errorRes(texts[position])) else null
                box.contentDescription = activity.getString(
                    if (incremental) R.string.load_typed_box_desc_incremental else R.string.load_typed_box_desc,
                    frame.label,
                )
                total.visibility = if (incremental) View.VISIBLE else View.GONE
                if (incremental) showTotal(TypedLoads.totals(boxes(), entry).getOrNull(position))
                binding = false
                bindThumb(frame.path)
            }

            fun showTotal(kg: Float?) {
                total.text = kg?.let { activity.getString(R.string.load_typed_total_fmt, formatKg(it)) }
                    ?: activity.getString(R.string.load_typed_total_none)
            }

            private fun errorRes(text: String): Int {
                val incremental = entry == Entry.INCREMENTAL
                return when {
                    text.isBlank() ->
                        if (incremental) R.string.load_typed_blank_incremental else R.string.load_typed_blank
                    TypedLoads.parseKg(text, entry) is TypedLoads.Parsed.Invalid ->
                        if (incremental) R.string.load_typed_invalid_incremental else R.string.load_typed_invalid
                    else -> R.string.load_typed_below_zero
                }
            }

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

    /**
     * Opens the keyboard on [box], now or once its window has focus again. Right
     * after the entry dropdown closes, its popup still holds window focus, and the
     * keyboard ignores a request from a window without it (TD-141).
     */
    private fun showKeyboard(box: EditText) {
        val keyboard = box.context.getSystemService(InputMethodManager::class.java) ?: return
        if (box.hasWindowFocus()) {
            keyboard.showSoftInput(box, 0)
            return
        }
        box.viewTreeObserver.addOnWindowFocusChangeListener(
            object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (!hasFocus) return
                    box.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                    if (box.isFocused) keyboard.showSoftInput(box, 0)
                }
            },
        )
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
        val text = String.format(Locale.US, "%.3f", kg).trimEnd('0').trimEnd('.')
        return if (text == "-0") "0" else text // a float's residue from a subtraction
    }

    /** The dropdown's rows, in order. */
    private val ENTRIES = listOf(
        Entry.ABSOLUTE to R.string.load_typed_entry_absolute,
        Entry.INCREMENTAL to R.string.load_typed_entry_incremental,
    )
    private const val UNSIGNED_DECIMAL = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    private const val SIGNED_DECIMAL = UNSIGNED_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED

    /** The row's android:digits: a decimal point or comma, whichever the keypad offers. */
    private const val DIGITS = "0123456789.,"

    /** Rows are small; keep them all bound so Next never lands on an unbound row. */
    private const val MAX_CACHED_ROWS = 60
    private const val THUMB_MAX_EDGE_PX = 160
}
