package com.sempermechanics.semper.ui.viewer

import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.sempermechanics.semper.databinding.PopupFieldOptionsBinding
import com.sempermechanics.semper.ui.viewer.inspect.ViewerInspectController
import com.sempermechanics.semper.ui.viewer.summary.ViewerSummaryController

/** What [FieldPopup] needs from the viewer. */
internal interface FieldPopupHost : ViewerViews {
    /** Holds the field on screen ([ResultViewerViewModel.field]); a pill tap sets it. */
    val viewModel: ResultViewerViewModel

    /** True while the summary animation, not a frame, is on screen. */
    val isShowingSummary: Boolean
}

/**
 * The field button's popup: a glass pill per field, the live one checked; a tap switches field
 * and has [scale], [summary] and [inspect] redraw for it.
 *
 * Constructed before onCreate; reads [ViewerViews.binding] lazily.
 */
internal class FieldPopup(
    private val activity: AppCompatActivity,
    private val host: FieldPopupHost,
    private val chrome: ViewerChromeController,
    private val scale: ViewerScaleController,
    private val summary: ViewerSummaryController,
    private val inspect: ViewerInspectController,
) {

    /** Glass-pill popup listing every field; the live field is checked. */
    fun show(anchor: View) {
        val popup = PopupFieldOptionsBinding.inflate(activity.layoutInflater)
        val window = PopupWindow(
            popup.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        window.isOutsideTouchable = true
        ViewerFieldPills.BY_ID.forEach { (id, field) ->
            // Looked up by id: the pills' id ↔ field map is ViewerFieldPills'.
            val button = popup.root.findViewById<MaterialButton>(id)
            button.text = field.label
            button.isCheckable = true
            button.isChecked = field == host.viewModel.field
            button.setOnClickListener {
                if (field == host.viewModel.field) {
                    window.dismiss()
                    return@setOnClickListener
                }
                // One value: the label and the data index can no longer disagree.
                host.viewModel.field = field
                host.binding.btnFieldFab.text = field.label
                // Caption + probe value are refreshed by updateVisualization once the
                // new field's metrics are computed off the main thread.
                scale.updateVisualization(field.dataIndex)
                summary.onFieldChanged()
                if (host.isShowingSummary) host.binding.tvFrameCounter.text = summary.counterText()
                inspect.refreshCrosshairs()
                chrome.bumpChrome()
                window.dismiss()
            }
        }
        window.showAsDropDown(anchor, 0, OFFSET_Y)
    }

    private companion object {
        /** The popup's drop below its button, in px. */
        const val OFFSET_Y = 4
    }
}
