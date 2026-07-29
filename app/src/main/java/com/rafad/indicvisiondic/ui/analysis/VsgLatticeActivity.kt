package com.rafad.indicvisiondic.ui.analysis

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.ui.common.Insets
import com.rafad.indicvisiondic.ui.viewer.ResultViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt

/**
 * The swept parameter space as a 2-D lattice (subset across, VSG up): solved
 * combinations filled, skipped ones hollow. It sits in front of the result
 * viewer for a sweep — both after a fresh run and when a saved sweep is
 * reopened from Home — so the frames have a map.
 *
 * It is interactive: tapping a solved node focuses its line-cut curve; double
 * tap, long-press, or "Open analysis" opens that specific result. "View
 * results" still opens the first frame. The lattice stays on the back stack
 * while the viewer is up, so Back from the viewer returns here.
 *
 * The Intent it receives is exactly the one the result viewer needs (plus the
 * sweep lattice arrays); it forwards those extras on, adding only the frame to
 * start at. So it never has to understand the viewer's payload.
 */
class VsgLatticeActivity : AppCompatActivity() {

    private companion object {
        val STRAIN_OPTIONS = listOf(
            R.string.field_exx to DicResult.IDX_EXX,
            R.string.field_eyy to DicResult.IDX_EYY,
            R.string.field_exy to DicResult.IDX_EXY,
        )
    }

    /** Decoded `.dat` payloads for each solved combination, in frame order. */
    private var frameData: List<FloatArray> = emptyList()

    /** Grid pitch per solved frame (from the sweep plan). */
    private var frameSteps: IntArray = IntArray(0)

    /** The solved frame currently focused on the lattice/plot; -1 means all. */
    private var focusedFrameIndex: Int = -1

    private lateinit var strainPlotSection: View
    private lateinit var latticeView: VsgLatticeView
    private lateinit var strainPlot: VsgPlotView
    private lateinit var strainSpinner: Spinner
    private lateinit var strainPlotTitle: TextView

    /** Blank until a drag; shows the scrubbed (x, y) of each plotted series. */
    private lateinit var strainPlotReadout: TextView
    private lateinit var btnOpenAnalysis: MaterialButton

    private data class FrameSeries(val frameIndex: Int, val series: VsgPlotView.Series)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vsg_lattice)

        // Edge-to-edge: push the toolbar below the status bar so its back arrow
        // lines up with the other screens' top bars (it would otherwise sit
        // under the clock).
        Insets.padTop(findViewById(R.id.toolbar))

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }

        // Solved nodes carry their frame index (their position in plan order,
        // which is the viewer's frame order); skipped ones have no frame.
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

        latticeView = findViewById<VsgLatticeView>(R.id.latticeView)
        latticeView.apply {
            interactionEnabled = true
            setNodes(nodes)
            onNodeClick = { node ->
                if (node.solved) {
                    toggleFocus(node.frameIndex)
                } else if (node.failureReason.isNotEmpty()) {
                    Toast.makeText(this@VsgLatticeActivity, node.failureReason, Toast.LENGTH_LONG).show()
                }
            }
            onNodeDoubleClick = { node -> if (node.solved) openViewer(node.frameIndex) }
            onNodeLongClick = { node -> if (node.solved) openViewer(node.frameIndex) }
        }

        showSummary(nodes, solved.size, skipped.size)

        strainPlotSection = findViewById(R.id.strainPlotSection)
        strainPlot = findViewById(R.id.plotLatticeStrain)
        strainSpinner = findViewById(R.id.spinnerStrainComponent)
        strainPlotTitle = findViewById(R.id.tvStrainPlotTitle)
        strainPlotReadout = findViewById(R.id.tvStrainPlotReadout)
        btnOpenAnalysis = findViewById(R.id.btnOpenAnalysis)
        btnOpenAnalysis.setOnClickListener {
            if (focusedFrameIndex >= 0) openViewer(focusedFrameIndex)
        }
        strainPlot.onScrub = { x, samples ->
            strainPlotReadout.text = samples.joinToString(" · ") { (label, y) ->
                getString(R.string.vsg_lattice_scrub_value_fmt, x, y, label)
            }
        }
        setupStrainSpinner()
        loadStrainProfiles()
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

    /** Reads one set of (subset, step, window) triples into lattice nodes. */
    /**
     * The headline under the lattice, and whether opening results is offered at
     * all — a sweep where nothing solved has nothing to view, so the button goes
     * rather than opening an empty viewer.
     */
    private fun showSummary(nodes: List<VsgLatticeView.Node>, solvedCount: Int, skippedCount: Int) {
        // The whole sweep shares one step fraction, subset ÷ D; recover D from a
        // node (step = round(subset / D)).
        val stepDenom = nodes.firstOrNull()?.takeIf { it.step > 0 }
            ?.let { (it.subset.toDouble() / it.step).roundToInt() } ?: 0
        val summary = findViewById<TextView>(R.id.tvLatticeSummary)
        val btnViewResults = findViewById<MaterialButton>(R.id.btnViewResults)
        if (solvedCount == 0) {
            summary.text = getString(R.string.vsg_lattice_all_failed)
            btnViewResults.visibility = View.GONE
            return
        }
        summary.text = if (stepDenom > 0) {
            getString(R.string.vsg_lattice_summary_fmt, nodes.size, solvedCount, skippedCount, stepDenom)
        } else {
            getString(R.string.vsg_lattice_summary_short_fmt, nodes.size, solvedCount, skippedCount)
        }
        btnViewResults.setOnClickListener { openViewer(0) }
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
                    ?.let { code -> getString(EngineFailure.reasonRes(code), code) }
                    .orEmpty(),
            )
        }
    }

    /**
     * Loads each solved combination's `.dat` and shows the centre-line strain
     * plot when at least one profile has data.
     */
    @Suppress("ReturnCount") // one bail per missing sweep extra before the load starts
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

    /** Rebuilds the line-cut plot for the selected Exx / Eyy / Exy component. */
    @Suppress("ReturnCount") // nothing to plot, or a dropped focus that re-enters after clearing it
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
        val labels = intent.getStringArrayListExtra(DicKeys.DEF_FILE_NAMES).orEmpty()
        val baseStep = intent.getIntExtra(DicKeys.STEP, 1).coerceAtLeast(1)

        val seriesByFrame = frameData.mapIndexedNotNull { index, data ->
            val step = frameSteps.getOrNull(index)?.coerceAtLeast(1) ?: baseStep
            val points = VsgStudy.profileAlong(data, component, line, step / 2f)
            if (points.isEmpty()) return@mapIndexedNotNull null
            FrameSeries(
                frameIndex = index,
                series = VsgPlotView.Series(
                    label = labels.getOrNull(index) ?: getString(R.string.sweep_frame_btn_fmt, index + 1),
                    color = strainPlot.paletteColor(index),
                    points = points,
                    markers = false,
                    muted = focusedFrameIndex >= 0 && index != focusedFrameIndex,
                ),
            )
        }
        if (seriesByFrame.isEmpty()) {
            strainPlotSection.visibility = View.GONE
            return
        }
        if (focusedFrameIndex >= 0 && seriesByFrame.none { it.frameIndex == focusedFrameIndex }) {
            focusedFrameIndex = -1
            latticeView.selectedFrameIndex = -1
            btnOpenAnalysis.visibility = View.GONE
            redrawStrainPlot()
            return
        }
        strainPlotSection.visibility = View.VISIBLE
        // The cut is always through the ROI centre; the title carries the axis
        // it runs along, the x axis label carries the position on it.
        strainPlotTitle.text = getString(
            R.string.line_cut_title_axis_fmt,
            getString(if (horizontal) R.string.axis_x else R.string.axis_y),
        )
        strainPlot.setData(
            seriesByFrame.map { it.series },
            getString(if (horizontal) R.string.line_cut_axis_x else R.string.line_cut_axis_y),
            getString(R.string.line_cut_axis_strain),
        )
        strainPlotReadout.text = ""
    }

    private fun toggleFocus(frameIndex: Int) {
        focusedFrameIndex = if (focusedFrameIndex == frameIndex) -1 else frameIndex
        latticeView.selectedFrameIndex = focusedFrameIndex
        btnOpenAnalysis.visibility = if (focusedFrameIndex >= 0) View.VISIBLE else View.GONE
        redrawStrainPlot()
    }

    private fun selectedStrainComponent(): Int {
        val index = strainSpinner.selectedItemPosition.coerceIn(0, STRAIN_OPTIONS.lastIndex)
        return STRAIN_OPTIONS[index].second
    }

    /**
     * Opens the result viewer at [frameIndex], forwarding this screen's own
     * extras. The lattice stays behind it, so Back returns here.
     */
    private fun openViewer(frameIndex: Int) {
        val extras = intent.extras ?: return
        startActivity(
            Intent(this, ResultViewerActivity::class.java)
                .putExtras(extras)
                .putExtra(DicKeys.START_FRAME, frameIndex),
        )
    }
}
