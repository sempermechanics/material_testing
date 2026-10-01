package com.indicvision.semper.ui.analysis.sweep

import com.indicvision.semper.data.session.SkippedNode
import com.indicvision.semper.field.DicParams

/**
 * The skipped-node record of a sweep combination the engine could not solve:
 * its subset, step and VSG in px ([VsgStudy.Point.vsg], not the window in
 * points), with the engine's [code]. The construction `AnalysisViewModel` and
 * `StaticAnalysisActivity` each spell out today.
 */
fun SkippedNode.Companion.of(point: VsgStudy.Point, code: Int): SkippedNode =
    SkippedNode(subset = point.subset, step = point.step, strainWindow = point.vsg, code = code)

/** The engine parameters of this combination: its strain window as the VSG in px the engine is handed. */
fun VsgStudy.Point.toDicParams(): DicParams = DicParams(subset = subset, step = step, strainWindow = vsg)
