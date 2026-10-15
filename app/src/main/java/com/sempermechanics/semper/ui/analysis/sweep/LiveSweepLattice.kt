package com.sempermechanics.semper.ui.analysis.sweep

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.run.RunOverlayText

/**
 * The run overlay's sweep lattice: every combination of the plan, drawn as a
 * waiting ring until its solve ends, then filled (solved) or a red ring
 * (skipped). The combination being solved now is ringed. Drawn with range
 * labels and bigger nodes (not [SweepLatticeView.compact]).
 *
 * [last], when given, says how the latest combination to end went: its
 * convergence and points, or "skipped" in red. Hidden until one has ended.
 */
class LiveSweepLattice(
    private val group: View,
    private val lattice: SweepLatticeView,
    private val last: TextView? = null,
) {

    private var plan: List<SweepStudy.Point> = emptyList()

    init {
        lattice.compact = false
    }

    /** Shows [plan] with nothing run yet; an empty plan hides the lattice. */
    fun show(plan: List<SweepStudy.Point>) {
        this.plan = plan
        lattice.setNodes(liveLatticeNodes(plan, emptyList(), current = 0))
        group.visibility = if (plan.isEmpty()) View.GONE else View.VISIBLE
        last?.visibility = View.GONE
    }

    fun hide() {
        plan = emptyList()
        group.visibility = View.GONE
        last?.visibility = View.GONE
    }

    /**
     * Redraws the lattice as of [tick], which carries every combination's
     * outcome. [solved] is the latest tick that ended a combination solved,
     * whose numbers the last line reads.
     */
    fun apply(tick: SweepStudyRunner.Progress, solved: SweepStudyRunner.Progress? = null) {
        if (plan.isEmpty()) return
        lattice.setNodes(liveLatticeNodes(plan, tick.outcomes, tick.runIndex))
        val line = last ?: return
        val said = RunOverlayText.sweepLast(line.resources, tick, solved)
        line.visibility = if (said != null) View.VISIBLE else View.GONE
        if (said != null) {
            line.text = said.text
            val color = if (said.skipped) R.color.semantic_danger else R.color.text_secondary
            line.setTextColor(ContextCompat.getColor(line.context, color))
        }
    }
}

/**
 * A running sweep's lattice: every combination of [plan], drawn as [outcomes]
 * has it so far (waiting until a tick says otherwise), with [current] ringed
 * while it is being solved.
 */
internal fun liveLatticeNodes(
    plan: List<SweepStudy.Point>,
    outcomes: List<SweepStudyRunner.NodeOutcome>,
    current: Int,
): List<SweepLatticeView.Node> = plan.mapIndexed { i, point ->
    val outcome = outcomes.getOrElse(i) { SweepStudyRunner.NodeOutcome.PENDING }
    val pending = outcome == SweepStudyRunner.NodeOutcome.PENDING
    SweepLatticeView.Node(
        subset = point.subset,
        step = point.step,
        window = point.window,
        vsg = point.vsg,
        solved = outcome == SweepStudyRunner.NodeOutcome.SOLVED,
        pending = pending,
        current = pending && i == current,
    )
}
