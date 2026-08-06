// One small method per thing the screen does — node taps, the summary, the
// strain plot, the coach mark — so TooManyFunctions is suppressed here.
@file:Suppress("TooManyFunctions")

@file:SuppressLint("PrivateResource")

package com.indicvision.semper.ui.analysis

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.DicKeys
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.data.CoachPrefs
import com.indicvision.semper.data.ParamClipboard
import com.indicvision.semper.ui.common.CoachMarkController
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.viewer.ResultViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * The swept parameter space as a 2-D lattice (subset across, strain window up):
 * solved combinations filled, skipped ones hollow. Staging screen in front of
 * the result viewer for a sweep — walk solved nodes one-thumb, read each strain
 * curve, then copy the winning params into single-analysis settings.
 *
 * Double-tap or long-press a solved node still opens that frame in the viewer.
 * The lattice stays on the back stack while the viewer is up.
 *
 * The Intent it receives is exactly the one the result viewer needs (plus the
 * sweep lattice arrays); it forwards those extras on, adding only the frame to
 * start at.
 */
class VsgLatticeActivity : AppCompatActivity() {

    private companion object {
        val STRAIN_OPTIONS = listOf(
            R.string.field_exx to DicResult.IDX_EXX,
            R.string.field_eyy to DicResult.IDX_EYY,
            R.string.field_exy to DicResult.IDX_EXY,
        )
        const val EXPORT_WIDTH_PX = 2400
        const val EXPORT_HEIGHT_PX = 1600
        const val PNG_QUALITY = 100
    }

    /** Decoded `.dat` payloads for each solved combination, in frame order. */
    private var frameData: List<FloatArray> = emptyList()

    /** Grid pitch per solved frame (from the sweep plan). */
    private var frameSteps: IntArray = IntArray(0)

    /** Solved nodes in lattice order (ascending subset, then window). */
    private var solvedNodes: List<VsgLatticeView.Node> = emptyList()

    /** The solved frame currently selected; always a solved index when any exist. */
    private var focusedFrameIndex: Int = -1

    private lateinit var strainPlotSection: View
    private lateinit var latticeView: VsgLatticeView
    private lateinit var strainPlot: VsgPlotView
    private lateinit var strainSpinner: Spinner
    private lateinit var strainPlotTitle: TextView
    private lateinit var strainPlotReadout: TextView
    private lateinit var stepperRow: View
    private lateinit var chipSelectedParams: Chip
    private lateinit var btnPrevNode: ImageButton
    private lateinit var btnNextNode: ImageButton
    private lateinit var togglePlotMode: MaterialButtonToggleGroup
    private lateinit var btnSaveGraph: MaterialButton
    private lateinit var btnCopyParams: MaterialButton

    private data class FrameSeries(val frameIndex: Int, val series: VsgPlotView.Series)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vsg_lattice)

        Insets.padTop(findViewById(R.id.toolbar))

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }

        val solved = nodesFrom(
            DicKeys.SWEEP_SUBSETS,
            DicKeys.SWEEP_STEPS,
            DicKeys.SWEEP_STRAIN_WINS,
            solved = true,
        )
        val skippedCodes = intent.getIntArrayExtra(DicKeys.SWEEP_SKIP_CODES) ?: IntArray(0)
        val skipped = nodesFrom(
            DicKeys.SWEEP_SKIP_SUBSETS,
            DicKeys.SWEEP_SKIP_STEPS,
            DicKeys.SWEEP_SKIP_STRAIN_WINS,
            solved = false,
            codes = skippedCodes,
        )
        val nodes = (solved + skipped).sortedWith(compareBy({ it.subset }, { it.window }))
        solvedNodes = nodes.filter { it.solved }

        latticeView = findViewById(R.id.latticeView)
        latticeView.apply {
            interactionEnabled = true
            setNodes(nodes)
            onNodeClick = { node ->
                if (node.solved) selectFocus(node.frameIndex) else showSkipReason(node)
            }
            onNodeDoubleClick = { node -> if (node.solved) openViewer(node.frameIndex) }
            onNodeLongClick = { node -> if (node.solved) openViewer(node.frameIndex) }
        }

        showSummary(nodes, solved.size, skipped.size)

        bindStrainControls()

        if (solvedNodes.isNotEmpty()) {
            selectFocus(solvedNodes.first().frameIndex)
        } else {
            stepperRow.visibility = View.GONE
            btnCopyParams.isEnabled = false
            btnSaveGraph.isEnabled = false
        }

        loadStrainProfiles()
        maybeCoachTheGraph()
    }

    /** Binds the strain-plot section views and wires their listeners. */
    private fun bindStrainControls() {
        strainPlotSection = findViewById(R.id.strainPlotSection)
        strainPlot = findViewById(R.id.plotLatticeStrain)
        strainPlot.zoomEnabled = true
        strainSpinner = findViewById(R.id.spinnerStrainComponent)
        strainPlotTitle = findViewById(R.id.tvStrainPlotTitle)
        strainPlotReadout = findViewById(R.id.tvStrainPlotReadout)
        stepperRow = findViewById(R.id.stepperRow)
        chipSelectedParams = findViewById(R.id.chipSelectedParams)
        btnPrevNode = findViewById(R.id.btnPrevNode)
        btnNextNode = findViewById(R.id.btnNextNode)
        togglePlotMode = findViewById(R.id.togglePlotMode)
        btnSaveGraph = findViewById(R.id.btnSaveGraph)
        btnCopyParams = findViewById(R.id.btnCopyParams)

        btnPrevNode.setOnClickListener { stepFocus(-1) }
        btnNextNode.setOnClickListener { stepFocus(1) }
        togglePlotMode.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) redrawStrainPlot()
        }
        btnCopyParams.setOnClickListener { copySelectedParams() }
        btnSaveGraph.setOnClickListener { saveGraph() }

        strainPlot.onScrub = { x, samples -> strainPlotReadout.text = scrubReadout(x, samples) }
        setupStrainSpinner()
    }

    /** Scrub readout for the selected node only: (x, y) and its param chip label. */
    private fun scrubReadout(x: Float, samples: List<VsgPlotView.Sample>): CharSequence {
        if (x.isNaN() || samples.isEmpty()) return ""
        val sample = samples.first()
        return getString(R.string.vsg_lattice_scrub_value_fmt, x, sample.value, sample.label)
    }

    private fun maybeCoachTheGraph() {
        latticeView.post {
            CoachMarkController(this).maybeShow(
                CoachPrefs.Screen.SWEEP_LATTICE,
                listOf(
                    CoachMarkController.Step(
                        latticeView,
                        getString(R.string.coach_sweep_graph),
                    ),
                    CoachMarkController.Step(
                        strainPlot,
                        getString(R.string.coach_sweep_scrub),
                    ),
                ),
            )
        }
    }

    private fun setupStrainSpinner() {
        val labels = STRAIN_OPTIONS.map { getString(it.first) }
        strainSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        strainSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                redrawStrainPlot()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showSummary(nodes: List<VsgLatticeView.Node>, solvedCount: Int, skippedCount: Int) {
        val stepDenom = nodes.firstOrNull()?.takeIf { it.step > 0 }
            ?.let { (it.subset.toDouble() / it.step).roundToInt() } ?: 0
        val summary = findViewById<TextView>(R.id.tvLatticeSummary)
        if (solvedCount == 0) {
            summary.text = getString(R.string.vsg_lattice_all_failed)
            return
        }
        summary.text = if (stepDenom > 0) {
            resources.getQuantityString(
                R.plurals.vsg_lattice_summary_fmt,
                nodes.size,
                nodes.size,
                solvedCount,
                skippedCount,
                stepDenom,
            )
        } else {
            resources.getQuantityString(
                R.plurals.vsg_lattice_summary_short_fmt,
                nodes.size,
                nodes.size,
                solvedCount,
                skippedCount,
            )
        }
    }

    private fun showSkipReason(node: VsgLatticeView.Node) {
        val reason = node.failureReason.ifEmpty { getString(R.string.sweep_node_skipped) }
        MaterialAlertDialogBuilder(this)
            .setTitle(
                getString(R.string.sweep_node_title_fmt, node.subset, node.step, node.window, node.vsg),
            )
            .setMessage(reason)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun nodesFrom(
        subsetsKey: String,
        stepsKey: String,
        windowsKey: String,
        solved: Boolean,
        codes: IntArray = IntArray(0),
    ): List<VsgLatticeView.Node> {
        val subsets = intent.getIntArrayExtra(subsetsKey) ?: IntArray(0)
        val steps = intent.getIntArrayExtra(stepsKey) ?: IntArray(0)
        val windows = intent.getIntArrayExtra(windowsKey) ?: IntArray(0)
        val count = minOf(subsets.size, steps.size, windows.size)
        return (0 until count).map { i ->
            VsgLatticeView.Node(
                subset = subsets[i],
                step = steps[i],
                window = windows[i],
                vsg = VsgStudy.vsgFor(steps[i], windows[i]),
                solved = solved,
                frameIndex = if (solved) i else -1,
                failureReason = codes.getOrNull(i)
                    ?.let { code -> getString(EngineFailure.shortReasonRes(code)) }
                    .orEmpty(),
            )
        }
    }

    @Suppress("ReturnCount")
    private fun loadStrainProfiles() {
        val batchDirPath = intent.getStringExtra(DicKeys.BATCH_DIR_PATH) ?: return
        val steps = intent.getIntArrayExtra(DicKeys.SWEEP_STEPS) ?: return
        if (steps.isEmpty()) return

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val dir = File(batchDirPath)
                if (!dir.isDirectory) return@withContext emptyList()
                val files = dir.listFiles { file -> file.extension == "dat" }
                    ?.sortedBy { it.name }
                    ?: return@withContext emptyList()
                files.mapNotNull { file ->
                    try {
                        DicResult.decodeDatBytes(file.readBytes())
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        Timber.w(e, "Failed to read %s", file.name)
                        null
                    }
                }
            }
            if (loaded.isEmpty()) return@launch
            frameData = loaded
            frameSteps = steps
            redrawStrainPlot()
        }
    }

    /** Rebuilds the line-cut plot for Highlight or Isolate mode. */
    @Suppress("ReturnCount")
    private fun redrawStrainPlot() {
        if (frameData.isEmpty()) {
            strainPlotSection.visibility = View.GONE
            return
        }
        val component = selectedStrainComponent()
        val horizontal = intent.getBooleanExtra(DicKeys.LINE_CUT_HORIZONTAL, true)
        val line = VsgStudy.centreLine(
            intent.getIntExtra(DicKeys.ROI_X, 0),
            intent.getIntExtra(DicKeys.ROI_Y, 0),
            intent.getIntExtra(DicKeys.ROI_W, 0),
            intent.getIntExtra(DicKeys.ROI_H, 0),
            horizontal,
        )
        val baseStep = intent.getIntExtra(DicKeys.STEP, 1).coerceAtLeast(1)
        val isolate = togglePlotMode.checkedButtonId == R.id.btnPlotIsolate

        val seriesByFrame = buildFrameSeries(component, line, baseStep)
        if (seriesByFrame.isEmpty()) {
            strainPlotSection.visibility = View.GONE
            return
        }
        if (focusedFrameIndex >= 0 && seriesByFrame.none { it.frameIndex == focusedFrameIndex }) {
            val fallback = seriesByFrame.first().frameIndex
            selectFocus(fallback)
            return
        }

        val toShow = if (isolate) {
            seriesByFrame.filter { it.frameIndex == focusedFrameIndex }
                .map { it.series.copy(muted = false) }
        } else {
            // Selected last so it paints bold on top of muted curves.
            val muted = seriesByFrame.filter { it.frameIndex != focusedFrameIndex }.map { it.series }
            val selected = seriesByFrame.filter { it.frameIndex == focusedFrameIndex }
                .map { it.series.copy(muted = false) }
            muted + selected
        }

        strainPlotSection.visibility = View.VISIBLE
        strainPlotTitle.text = getString(
            R.string.line_cut_title_axis_fmt,
            getString(if (horizontal) R.string.axis_x else R.string.axis_y),
        )
        strainPlot.setData(
            toShow,
            getString(if (horizontal) R.string.line_cut_axis_x else R.string.line_cut_axis_y),
            getString(R.string.line_cut_axis_strain),
        )
        strainPlotReadout.text = ""
    }

    /** One plot series per solved frame along [line], muted except the focused one. */
    private fun buildFrameSeries(
        component: Int,
        line: VsgStudy.StudyLine,
        baseStep: Int,
    ): List<FrameSeries> =
        frameData.mapIndexedNotNull { index, data ->
            val node = solvedNodes.find { it.frameIndex == index }
            val step = frameSteps.getOrNull(index)?.coerceAtLeast(1) ?: baseStep
            val points = VsgStudy.profileAlong(data, component, line, step / 2f)
            if (points.isEmpty()) return@mapIndexedNotNull null
            val label = if (node != null) {
                getString(R.string.vsg_lattice_param_fmt, node.subset, node.step, node.window)
            } else {
                getString(R.string.sweep_frame_btn_fmt, index + 1)
            }
            FrameSeries(
                frameIndex = index,
                series = VsgPlotView.Series(
                    label = label,
                    color = strainPlot.paletteColor(index),
                    points = points,
                    markers = false,
                    muted = index != focusedFrameIndex,
                ),
            )
        }

    private fun selectFocus(frameIndex: Int) {
        focusedFrameIndex = frameIndex
        latticeView.selectedFrameIndex = focusedFrameIndex
        updateChipAndStepper()
        redrawStrainPlot()
    }

    private fun stepFocus(delta: Int) {
        if (solvedNodes.isEmpty()) return
        val current = solvedNodes.indexOfFirst { it.frameIndex == focusedFrameIndex }
            .coerceAtLeast(0)
        val next = (current + delta).coerceIn(0, solvedNodes.lastIndex)
        selectFocus(solvedNodes[next].frameIndex)
    }

    private fun updateChipAndStepper() {
        val node = solvedNodes.find { it.frameIndex == focusedFrameIndex }
        if (node == null) {
            stepperRow.visibility = View.GONE
            btnCopyParams.isEnabled = false
            return
        }
        stepperRow.visibility = View.VISIBLE
        chipSelectedParams.text = getString(
            R.string.vsg_lattice_param_fmt,
            node.subset,
            node.step,
            node.window,
        )
        val idx = solvedNodes.indexOfFirst { it.frameIndex == focusedFrameIndex }
        btnPrevNode.isEnabled = idx > 0
        btnNextNode.isEnabled = idx in 0 until solvedNodes.lastIndex
        btnCopyParams.isEnabled = true
        btnSaveGraph.isEnabled = true
    }

    private fun selectedNode(): VsgLatticeView.Node? =
        solvedNodes.find { it.frameIndex == focusedFrameIndex }

    private fun copySelectedParams() {
        val node = selectedNode() ?: return
        ParamClipboard.copy(this, node.subset, node.step, node.window)
        Toast.makeText(this, R.string.vsg_lattice_params_copied, Toast.LENGTH_SHORT).show()
    }

    private fun saveGraph() {
        if (frameData.isEmpty() || focusedFrameIndex < 0) return
        lifecycleScope.launch {
            val bitmap = strainPlot.renderToBitmap(EXPORT_WIDTH_PX, EXPORT_HEIGHT_PX)
            val file = withContext(Dispatchers.IO) {
                writePng(bitmap)
            }
            bitmap.recycle()
            if (file == null) {
                Toast.makeText(this@VsgLatticeActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            sharePng(file)
        }
    }

    private fun writePng(bitmap: Bitmap): File? {
        return try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "vsg_strain_graph_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            }
            file
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Failed to write strain graph PNG")
            null
        }
    }

    private fun sharePng(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.vsg_lattice_share_graph)))
    }

    private fun selectedStrainComponent(): Int {
        val index = strainSpinner.selectedItemPosition.coerceIn(0, STRAIN_OPTIONS.lastIndex)
        return STRAIN_OPTIONS[index].second
    }

    private fun openViewer(frameIndex: Int) {
        val extras = intent.extras ?: return
        startActivity(
            Intent(this, ResultViewerActivity::class.java)
                .putExtras(extras)
                .putExtra(DicKeys.START_FRAME, frameIndex),
        )
    }
}
