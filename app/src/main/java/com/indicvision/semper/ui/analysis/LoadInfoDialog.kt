@file:SuppressLint("InflateParams")

package com.indicvision.semper.ui.analysis

import android.annotation.SuppressLint
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R
import com.indicvision.semper.data.TestType

/**
 * The machine-load card's ⓘ: the header the machine's CSV needs, a drawing
 * of the card's dimensions ([LoadInfoDiagramView]), then how the loads become
 * stress and E. Tensile draws the cross-section and the loading axis; bending
 * draws the beam's measurements and where **Set beam height** taps.
 */
object LoadInfoDialog {

    /** One test's texts, all string resources. */
    internal data class Texts(
        val csvSample: Int,
        val csvBody: Int,
        val diagramHeading: Int,
        val diagramBody: Int,
        val resultsBody: Int,
    )

    internal fun textsFor(type: TestType): Texts = when (type) {
        TestType.BENDING -> Texts(
            csvSample = R.string.info_load_csv_sample_bending,
            csvBody = R.string.info_load_csv_body_bending,
            diagramHeading = R.string.info_load_diagram_heading_bending,
            diagramBody = R.string.info_load_diagram_body_bending,
            resultsBody = R.string.info_load_body_bending,
        )
        // Plain DIC has no load card, so never opens this; it reads as tensile.
        TestType.TENSILE, TestType.DIC_2D -> Texts(
            csvSample = R.string.info_load_csv_sample_tensile,
            csvBody = R.string.info_load_csv_body_tensile,
            diagramHeading = R.string.info_load_diagram_heading_tensile,
            diagramBody = R.string.info_load_diagram_body_tensile,
            resultsBody = R.string.info_load_body,
        )
    }

    fun show(activity: AppCompatActivity, type: TestType) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_load_info, null)
        val texts = textsFor(type)
        // getText keeps the <b> spans in the strings.
        view.findViewById<TextView>(R.id.tvLoadInfoCsvSample).setText(texts.csvSample)
        view.findViewById<TextView>(R.id.tvLoadInfoCsvBody).text = activity.getText(texts.csvBody)
        view.findViewById<TextView>(R.id.tvLoadInfoDiagramHeading).setText(texts.diagramHeading)
        view.findViewById<LoadInfoDiagramView>(R.id.loadInfoDiagram).testType = type
        view.findViewById<TextView>(R.id.tvLoadInfoDiagramBody).text = activity.getText(texts.diagramBody)
        view.findViewById<TextView>(R.id.tvLoadInfoResultsBody).setText(texts.resultsBody)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.info_load_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
