package com.indicvision.semper.ui.analysis

import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R
import com.indicvision.semper.data.DocumentText
import com.indicvision.semper.data.LoadCsvError
import com.indicvision.semper.data.LoadCsvParse
import com.indicvision.semper.data.LoadCsvWarning
import com.indicvision.semper.data.LoadMapWarning
import com.indicvision.semper.data.LoadMapping
import com.indicvision.semper.data.MachineLoadCsv
import com.indicvision.semper.data.MachineLoadMapper
import com.indicvision.semper.data.MachineLoadTable
import com.indicvision.semper.data.TestType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.util.Locale

/**
 * The machine-load card on wizard step 1: import / clear the load log, the
 * specimen dimensions the test's stress needs (cross-section, or the bending
 * rows of [SpecimenGeometryFields] and [LoadPointRow]), strain axis, and the chips explaining
 * how the log's rows were matched to the frames. Owns
 * nothing the ViewModel does not already hold; [refresh] redraws from it.
 * The document picker and the load-point editor stay on the Activity
 * (Activity Result launchers must be registered there); [Pickers] opens them.
 *
 * Readiness stays in [AnalysisReadyGate].
 */
class AnalysisLoadCard(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    root: View,
    private val pickers: Pickers,
    private val onChanged: () -> Unit,
    private val confirmOpenFaq: (String) -> Unit,
) {
    /** The Activity's launchers: the load CSV, and bending's thickness-tap editor. */
    class Pickers(val loadCsv: () -> Unit, val loadPoint: () -> Unit = {})

    private val dropzone: View = root.findViewById(R.id.loadDropzone)
    private val summary: View = root.findViewById(R.id.loadSummary)
    private val tvName: TextView = root.findViewById(R.id.tvLoadName)
    private val tvMeta: TextView = root.findViewById(R.id.tvLoadMeta)
    private val warnRow: View = root.findViewById(R.id.loadWarnRow)
    private val tvWarn: TextView = warnRow.findViewById(R.id.tvWarnText)
    private val etCrossSection: EditText = root.findViewById(R.id.etCrossSection)
    private val toggleAxis: MaterialButtonToggleGroup = root.findViewById(R.id.toggleLoadAxis)

    /** Bending's tapped edges; hidden (and the axis toggle shown) for tensile. */
    val loadPoint = LoadPointRow(root, viewModel, pickers.loadPoint, onChanged)

    /** When a video's frames are time-matched: the log's start after the reference frame. */
    private val loadSync = LoadSyncRow(root, viewModel, onChanged)

    private var reading = false

    init {
        dropzone.setOnClickListener { if (!reading) pickers.loadCsv() }
        root.findViewById<ImageButton>(R.id.btnLoadClear).setOnClickListener {
            viewModel.clearMachineLoads()
            refresh()
            onChanged()
        }
        root.findViewById<ImageButton>(R.id.btnLoadInfo).setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.info_load_title)
                .setMessage(infoBodyRes(viewModel.testType))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        // Tensile enters an area; bending its own dimensions.
        root.findViewById<View>(R.id.rowCrossSection).isVisible = viewModel.testType == TestType.TENSILE
        SpecimenGeometryFields(root, viewModel, onChanged)
        warnRow.findViewById<ImageButton>(R.id.btnWarnFaq).setOnClickListener {
            confirmOpenFaq(activity.getString(R.string.url_faq_load_csv))
        }
        if (viewModel.crossSectionMm2 > 0f) {
            etCrossSection.setText(formatArea(viewModel.crossSectionMm2))
        }
        etCrossSection.doAfterTextChanged { text ->
            viewModel.crossSectionMm2 = text?.toString()?.trim()?.toFloatOrNull()?.takeIf { it > 0f } ?: 0f
            onChanged()
        }
        toggleAxis.check(if (viewModel.loadAxisX) R.id.btnLoadAxisX else R.id.btnLoadAxisY)
        toggleAxis.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) viewModel.loadAxisX = checkedId == R.id.btnLoadAxisX
        }
        refresh()
    }

    /**
     * Reads, parses and matches the picked document off the main thread. The
     * parsed log is cached on the ViewModel so a later change to the frames
     * only re-runs the match.
     */
    fun onCsvPicked(uri: Uri) {
        if (reading) return
        reading = true
        tvMeta.setText(R.string.load_reading)
        activity.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { readAndParse(uri) }
            reading = false
            when (outcome) {
                is ReadOutcome.Parsed -> {
                    viewModel.parsedLoadCsv = outcome.parse.csv
                    viewModel.loadCsvName = outcome.name
                    viewModel.refreshMachineLoads()
                }
                is ReadOutcome.Failed -> showError(outcome.message)
            }
            refresh()
            onChanged()
        }
    }

    /** Redraws the card from the ViewModel; call after the frames change too. */
    fun refresh() {
        loadPoint.refresh()
        viewModel.refreshMachineLoads()
        val parsed = viewModel.parsedLoadCsv
        val hasLog = parsed != null
        dropzone.isVisible = !hasLog
        summary.isVisible = hasLog
        loadSync.refresh(viewModel.machineLoads?.mapping == LoadMapping.TIME_NEAREST)
        if (parsed == null) {
            warnRow.isVisible = false
            return
        }
        tvName.text = viewModel.loadCsvName
        val table = viewModel.machineLoads
        tvMeta.text = if (table == null) {
            activity.resources.getQuantityString(
                R.plurals.load_meta_waiting_fmt,
                parsed.rows,
                parsed.rows,
                parsed.unit.label,
            )
        } else {
            activity.resources.getQuantityString(
                R.plurals.load_meta_rows_fmt,
                parsed.rows,
                parsed.rows,
                parsed.unit.label,
                activity.getString(mappingRes(table.mapping)),
            )
        }
        val warnings = buildList {
            parsed.warnings.forEach { add(csvWarningText(it)) }
            table?.warnings?.forEach { add(mapWarningText(it, table)) }
        }
        warnRow.isVisible = warnings.isNotEmpty()
        tvWarn.text = warnings.joinToString("\n")
    }

    private sealed interface ReadOutcome {
        data class Parsed(val parse: LoadCsvParse.Ok, val name: String) : ReadOutcome
        data class Failed(val message: String) : ReadOutcome
    }

    @Suppress("ReturnCount") // one exit per way a document can fail to be read
    private fun readAndParse(uri: Uri): ReadOutcome {
        val name = DocumentText.displayName(activity, uri)
        val text = try {
            DocumentText.read(activity, uri, MAX_BYTES)
                ?: return ReadOutcome.Failed(
                    activity.getString(R.string.load_err_too_large, MAX_BYTES / BYTES_PER_MB),
                )
        } catch (e: IOException) {
            Timber.w(e, "Load log unreadable")
            return ReadOutcome.Failed(activity.getString(R.string.load_err_read))
        } catch (e: SecurityException) {
            Timber.w(e, "Load log not accessible")
            return ReadOutcome.Failed(activity.getString(R.string.load_err_read))
        }
        return when (val parse = MachineLoadCsv.parse(text)) {
            is LoadCsvParse.Ok -> ReadOutcome.Parsed(parse, name)
            is LoadCsvParse.Failed -> ReadOutcome.Failed(errorText(parse.error))
        }
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.load_err_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun errorText(error: LoadCsvError): String = when (error) {
        LoadCsvError.Empty -> activity.getString(R.string.load_err_empty)
        LoadCsvError.TooManyRows ->
            activity.getString(R.string.load_err_too_many_rows, "%,d".format(Locale.US, MachineLoadCsv.MAX_ROWS))
        LoadCsvError.NoLoadColumn -> activity.getString(R.string.load_err_no_load_column)
        is LoadCsvError.NonNumeric ->
            activity.getString(R.string.load_err_non_numeric, error.line, error.column, error.text)
    }

    private fun csvWarningText(warning: LoadCsvWarning): String = activity.getString(
        when (warning) {
            LoadCsvWarning.UNITS_ASSUMED_N -> R.string.load_warn_units_assumed
            LoadCsvWarning.COLUMN_GUESSED -> R.string.load_warn_column_guessed
            LoadCsvWarning.TIME_IGNORED -> R.string.load_warn_time_ignored
        },
    )

    private fun mapWarningText(warning: LoadMapWarning, table: MachineLoadTable): String = when (warning) {
        LoadMapWarning.FIRST_ROW_DROPPED -> activity.getString(R.string.load_warn_first_row_dropped)
        LoadMapWarning.RESAMPLED -> activity.resources.getQuantityString(
            R.plurals.load_warn_resampled_fmt,
            table.sourceRows,
            table.sourceRows,
            table.loadsN.size,
        )
        LoadMapWarning.TIME_ALIGNED -> activity.getString(R.string.load_warn_time_aligned)
        LoadMapWarning.UNMATCHED_FRAMES -> (table.loadsN.size - table.matchedFrames).let { unmatched ->
            activity.resources.getQuantityString(
                R.plurals.load_warn_unmatched_fmt,
                unmatched,
                unmatched,
                table.loadsN.size,
                MachineLoadMapper.MATCH_TOLERANCE_MS.toInt(),
            )
        }
        LoadMapWarning.SIGN_UNEXPECTED -> activity.getString(R.string.load_warn_sign_tensile)
    }

    private fun infoBodyRes(testType: TestType): Int = when (testType) {
        TestType.TENSILE -> R.string.info_load_body
        TestType.BENDING -> R.string.info_load_body_bending
    }

    private fun mappingRes(mapping: LoadMapping): Int = when (mapping) {
        LoadMapping.ONE_TO_ONE -> R.string.load_mapping_one_to_one
        LoadMapping.ONE_TO_ONE_DROP_FIRST -> R.string.load_mapping_drop_first
        LoadMapping.TIME_NEAREST -> R.string.load_mapping_time
        LoadMapping.RESAMPLED -> R.string.load_mapping_resampled
    }

    private fun formatArea(area: Float): String =
        if (area == area.toLong().toFloat()) area.toLong().toString() else String.format(Locale.US, "%.3f", area)

    companion object {
        private const val BYTES_PER_MB = 1024 * 1024
        private const val MAX_BYTES = 8 * BYTES_PER_MB

        // Providers label CSV inconsistently (text/plain, octet-stream, …), so
        // the wildcard is the safety net rather than a fourth guess.
        val CSV_MIME_TYPES = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")
    }
}
