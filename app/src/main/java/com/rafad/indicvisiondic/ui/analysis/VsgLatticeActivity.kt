package com.rafad.indicvisiondic.ui.analysis

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.ui.common.Insets
import com.rafad.indicvisiondic.ui.viewer.ResultViewerActivity
import kotlin.math.roundToInt

/**
 * The swept parameter space as a 2-D lattice (subset across, VSG up): solved
 * combinations filled, skipped ones hollow. It sits in front of the result
 * viewer for a sweep — both after a fresh run and when a saved sweep is
 * reopened from Home — so the frames have a map.
 *
 * It is interactive: tapping a solved node opens that analysis, and "View
 * results" opens the first. The lattice stays on the back stack while the
 * viewer is up, so Back from the viewer returns here rather than skipping past.
 *
 * The Intent it receives is exactly the one the result viewer needs (plus the
 * sweep lattice arrays); it forwards those extras on, adding only the frame to
 * start at. So it never has to understand the viewer's payload.
 */
class VsgLatticeActivity : AppCompatActivity() {

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
        val skipped = nodesFrom(
            DicKeys.SWEEP_SKIP_SUBSETS,
            DicKeys.SWEEP_SKIP_STEPS,
            DicKeys.SWEEP_SKIP_STRAIN_WINS,
            solved = false,
        )
        val nodes = (solved + skipped).sortedWith(compareBy({ it.subset }, { it.vsg }))

        findViewById<VsgLatticeView>(R.id.latticeView).apply {
            setNodes(nodes)
            onNodeClick = { node -> if (node.frameIndex >= 0) openViewer(node.frameIndex) }
        }

        // The whole sweep shares one step fraction, subset ÷ D; recover D from a
        // node (step = round(subset / D)).
        val stepDenom = nodes.firstOrNull()?.takeIf { it.step > 0 }
            ?.let { (it.subset.toDouble() / it.step).roundToInt() } ?: 0
        findViewById<TextView>(R.id.tvLatticeSummary).text = if (stepDenom > 0) {
            getString(R.string.vsg_lattice_summary_fmt, nodes.size, solved.size, skipped.size, stepDenom)
        } else {
            getString(R.string.vsg_lattice_summary_short_fmt, nodes.size, solved.size, skipped.size)
        }
        findViewById<MaterialButton>(R.id.btnViewResults).setOnClickListener { openViewer(0) }
    }

    /** Reads one set of (subset, step, window) triples into lattice nodes. */
    private fun nodesFrom(
        subsetsKey: String,
        stepsKey: String,
        windowsKey: String,
        solved: Boolean,
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
            )
        }
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
