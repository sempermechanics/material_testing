package com.sempermechanics.semper.ui.examples

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.examples.ExampleManifest
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.databinding.SheetExampleDetailBinding
import com.sempermechanics.semper.ui.common.TestTypeSheet
import com.sempermechanics.semper.ui.common.auth.ExternalLinks
import com.sempermechanics.semper.ui.common.dialog.inflateSheet

/**
 * One example set: what it is, how the wizard will be set up, what to
 * expect, and the credit its licence asks for, with Try it and Save to phone.
 */
internal object ExampleDetailSheet {

    fun show(activity: AppCompatActivity, set: ExampleManifest, onTry: () -> Unit, onSave: () -> Unit) {
        val sheet = inflateSheet(activity, R.layout.sheet_example_detail)
        val views = SheetExampleDetailBinding.bind(sheet.view)
        views.tvDetailTitle.text = set.title
        views.tvDetailSummary.text = set.summary
        views.tvDetailSetup.text = setupText(activity, set)
        views.tvDetailExpected.text = expectedText(activity, set)
        views.tvDetailCredit.text = creditText(activity, set)
        views.btnDetailSource.setOnClickListener { ExternalLinks.open(activity, set.credit.url) }
        views.btnDetailLicence.setOnClickListener { ExternalLinks.open(activity, set.credit.licenceUrl) }
        views.btnDetailTry.setOnClickListener {
            sheet.dismiss()
            onTry()
        }
        views.btnDetailSave.setOnClickListener {
            sheet.dismiss()
            onSave()
        }
        // Half-open, the sheet put Try it under the navigation bar and hid Save to phone (Galaxy S22).
        sheet.dialog.behavior.skipCollapsed = true
        sheet.dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }

    /** The test, the frames, the dimensions Try it fills in, and the taps it leaves to the student. */
    fun setupText(context: Context, set: ExampleManifest): String {
        val type = set.testType ?: TestType.DIC_2D
        val lines = mutableListOf(
            context.resources.getQuantityString(
                R.plurals.examples_setup_fmt,
                set.deformed.size,
                context.getString(TestTypeSheet.labelRes(type)),
                set.deformed.size,
                set.imageSize[0],
                set.imageSize[1],
            ),
        )
        val specimen = set.specimen
        when {
            specimen == null -> Unit
            type == TestType.TENSILE -> lines += context.getString(
                R.string.examples_setup_tensile_fmt,
                specimen.crossSectionMm2,
                if (specimen.loadAxisX) "x" else "y",
            )
            else -> lines += context.getString(
                R.string.examples_setup_bending_fmt,
                specimen.spanMm,
                specimen.widthMm,
                specimen.thicknessMm,
            )
        }
        if (specimen?.spanIsPlaceholder == true) lines += context.getString(R.string.examples_setup_span_placeholder)
        set.loadLog?.let { lines += context.getString(R.string.examples_setup_load_log_fmt, it.file, it.unit) }
        set.tapHint?.let { lines += context.getString(R.string.examples_setup_taps_fmt, it.x, it.topY, it.bottomY) }
        return lines.joinToString("\n")
    }

    fun expectedText(context: Context, set: ExampleManifest): String = set.expected.joinToString("\n") { row ->
        val fromApp = row.source == ExampleManifest.SOURCE_APP
        val source = context.getString(if (fromApp) R.string.examples_source_app else R.string.examples_source_dataset)
        val line = context.getString(R.string.examples_expected_row_fmt, row.label, row.value, source)
        row.note?.let { "$line\n   $it" } ?: line
    }

    /** Creator, title, publisher, year, DOI, licence, what was changed, no endorsement: CC BY 4.0 §3(a). */
    fun creditText(context: Context, set: ExampleManifest): String {
        val c = set.credit
        val source = c.doi?.let { "doi:$it" } ?: c.url
        return context.getString(
            R.string.examples_credit_fmt,
            c.creators,
            c.title,
            c.publisher,
            c.year,
            source,
            c.licence,
            c.changes,
        )
    }
}
