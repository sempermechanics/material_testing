@file:SuppressLint("InflateParams")
@file:Suppress("MagicNumber", "TooManyFunctions")

package com.indicvision.semper.ui.common

import android.annotation.SuppressLint
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.indicvision.semper.R
import com.indicvision.semper.data.CoachPrefs

/**
 * In-sheet Images / Files picker. The host Activity owns permission and SAF
 * launchers (they must be registered before STARTED); this owns the grid.
 */
class MediaPickerSheet private constructor(
    private val activity: AppCompatActivity,
    private val mode: MediaSourceChooser.Mode,
    private val requestPermission: () -> Unit,
    private val onBrowseSaf: () -> Unit,
    private val onPicked: (List<Uri>) -> Unit,
) {
    private val sheet = BottomSheetDialog(activity)
    private val root: View = activity.layoutInflater.inflate(R.layout.sheet_media_picker, null)
    private val title: TextView = root.findViewById(R.id.tvMediaTitle)
    private val toggle: MaterialButtonToggleGroup = root.findViewById(R.id.toggleMediaSource)
    private val btnImages: View = root.findViewById(R.id.btnMediaImages)
    private val btnFiles: View = root.findViewById(R.id.btnMediaFiles)
    private val btnBrowse: View = root.findViewById(R.id.btnMediaBrowse)
    private val btnSelectAll: View = root.findViewById(R.id.btnMediaSelectAll)
    private val btnUse: MaterialButton = root.findViewById(R.id.btnMediaUse)
    private val btnAllow: View = root.findViewById(R.id.btnMediaAllow)
    private val empty: View = root.findViewById(R.id.mediaEmpty)
    private val emptyText: TextView = root.findViewById(R.id.tvMediaEmpty)
    private val list: RecyclerView = root.findViewById(R.id.listMedia)
    private val coach = CoachMarkController(activity)
    private val selected = linkedSetOf<Uri>()
    private var filesTab = false
    private val includeVideo = mode == MediaSourceChooser.Mode.HOME_REFERENCE
    private val multi = mode == MediaSourceChooser.Mode.DEFORMED

    private val adapter = MediaGridAdapter(
        isSelected = { selected.contains(it) },
        onClick = { item -> onTile(item) },
    )

    init {
        title.setText(
            when (mode) {
                MediaSourceChooser.Mode.HOME_REFERENCE -> R.string.new_analysis_title
                MediaSourceChooser.Mode.REFERENCE -> R.string.reference_image
                MediaSourceChooser.Mode.DEFORMED -> R.string.deformed_frames
            },
        )
        list.layoutManager = GridLayoutManager(activity, 3)
        list.adapter = adapter
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            filesTab = checkedId == R.id.btnMediaFiles
            reload()
        }
        btnBrowse.setOnClickListener {
            sheet.dismiss()
            onBrowseSaf()
        }
        btnSelectAll.setOnClickListener { selectAll() }
        btnAllow.setOnClickListener { requestPermission() }
        btnUse.setOnClickListener { confirm() }
        sheet.setOnDismissListener {
            coach.dismiss(markSeen = false)
            adapter.shutdown()
        }
        sheet.setContentView(root)
        root.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        sheet.setOnShowListener {
            expand()
            showToast()
            root.post { maybeCoach() }
        }
        reload()
        sheet.show()
    }

    fun onPermissionResult() {
        reload()
    }

    private fun expand() {
        val bottom = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        bottom.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        BottomSheetBehavior.from(bottom).apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun reload() {
        btnSelectAll.isVisible = multi && filesTab
        if (!MediaStoreBrowser.hasReadAccess(activity)) {
            adapter.submit(emptyList())
            showEmpty(needPermission = true)
            return
        }
        val items = runCatching {
            MediaStoreBrowser.query(
                context = activity,
                includeVideo = includeVideo,
                downloadsOnly = filesTab,
            )
        }.getOrDefault(emptyList())
        adapter.submit(items)
        empty.isVisible = items.isEmpty()
        list.isVisible = items.isNotEmpty()
        btnAllow.isVisible = false
        emptyText.setText(if (filesTab) R.string.media_empty_files else R.string.media_empty)
        refreshUse()
    }

    private fun showEmpty(needPermission: Boolean) {
        empty.isVisible = true
        list.isVisible = false
        btnAllow.isVisible = needPermission
        emptyText.setText(
            if (needPermission) R.string.media_need_permission else R.string.media_empty,
        )
    }

    private fun onTile(item: MediaStoreBrowser.Item) {
        if (!multi) {
            onPicked(listOf(item.uri))
            sheet.dismiss()
            return
        }
        if (!selected.add(item.uri)) selected.remove(item.uri)
        adapter.notifyDataSetChanged()
        refreshUse()
    }

    private fun selectAll() {
        adapter.items().forEach { selected.add(it.uri) }
        adapter.notifyDataSetChanged()
        refreshUse()
    }

    private fun refreshUse() {
        btnUse.isVisible = multi && selected.isNotEmpty()
        if (btnUse.isVisible) {
            btnUse.text = activity.resources.getQuantityString(
                R.plurals.media_use_fmt,
                selected.size,
                selected.size,
            )
        }
    }

    private fun confirm() {
        if (selected.isEmpty()) return
        onPicked(selected.toList())
        sheet.dismiss()
    }

    private fun showToast() {
        val overlay = sheet.window?.decorView as? ViewGroup ?: return
        val msg = when (mode) {
            MediaSourceChooser.Mode.DEFORMED -> activity.getString(R.string.picker_select_deformed)
            else -> activity.getString(R.string.picker_select_reference_toast)
        }
        CrispToast.show(activity, msg, overlayRoot = overlay, fromTop = true)
    }

    private fun maybeCoach() {
        val overlay = sheet.window?.decorView as? FrameLayout ?: return
        val screen = if (multi) CoachPrefs.Screen.MEDIA_PICKER_DEF else CoachPrefs.Screen.MEDIA_PICKER_REF
        val steps = buildList {
            add(
                CoachMarkController.Step(
                    btnImages,
                    activity.getString(R.string.coach_picker_images),
                ),
            )
            add(
                CoachMarkController.Step(
                    btnFiles,
                    activity.getString(R.string.coach_picker_files),
                ),
            )
            if (multi) {
                add(
                    CoachMarkController.Step(
                        list,
                        activity.getString(R.string.coach_picker_multi),
                        onEnter = {
                            toggle.check(R.id.btnMediaImages)
                        },
                    ),
                )
                add(
                    CoachMarkController.Step(
                        btnSelectAll,
                        activity.getString(R.string.coach_picker_select_all),
                        onEnter = {
                            toggle.check(R.id.btnMediaFiles)
                        },
                    ),
                )
            }
        }
        coach.maybeShow(screen, steps, overlayParent = overlay)
    }

    companion object {
        fun show(
            activity: AppCompatActivity,
            mode: MediaSourceChooser.Mode,
            requestPermission: () -> Unit,
            onBrowseSaf: () -> Unit,
            onPicked: (List<Uri>) -> Unit,
        ): MediaPickerSheet = MediaPickerSheet(
            activity,
            mode,
            requestPermission,
            onBrowseSaf,
            onPicked,
        )
    }
}
