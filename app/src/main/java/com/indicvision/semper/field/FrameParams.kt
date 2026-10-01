package com.indicvision.semper.field

/**
 * The solver parameters of every frame of one result.
 *
 * An ordinary analysis solves each frame with [base]. A parameter sweep
 * varies the parameters instead of the image, so it carries per-frame lists,
 * index-aligned with the solved frames; there [base] describes only the first
 * frame. Today the lists live on `SessionRecord` (`sweepSubsets`,
 * `sweepSteps`, `sweepStrainWindows`), `ViewerSweepArgs`, the viewer's
 * `IntArray?` fields and `ShareCenter.Snapshot`'s `*PerFrame` arrays.
 *
 * [at] is the lookup each of those sites spells out: the list's value at the
 * index, else the base value. A record's empty list and the viewer's null
 * array both mean "not a sweep" and fall back the same way.
 */
data class FrameParams(
    val base: DicParams,
    val subsets: List<Int> = emptyList(),
    val steps: List<Int> = emptyList(),
    val strainWindows: List<Int> = emptyList(),
) {

    /** True when the frames are parameter combinations; `SessionRecord.isSweep`'s `sweepSteps.isNotEmpty()`. */
    val isSweep: Boolean get() = steps.isNotEmpty()

    /**
     * Frame [index]'s parameters. Each of the three falls back to [base]
     * separately when its list is empty or too short:
     * `sweepSubsets.getOrElse(i) { subset }` and so on.
     */
    fun at(index: Int): DicParams = DicParams(
        subset = subsets.getOrElse(index) { base.subset },
        step = steps.getOrElse(index) { base.step },
        strainWindow = strainWindows.getOrElse(index) { base.strainWindow },
    )

    companion object {
        /** From the viewer's nullable arrays; null reads as an empty list, which is `getOrNull(i) ?: base`. */
        fun of(base: DicParams, subsets: IntArray?, steps: IntArray?, strainWindows: IntArray?): FrameParams =
            FrameParams(
                base = base,
                subsets = subsets?.toList().orEmpty(),
                steps = steps?.toList().orEmpty(),
                strainWindows = strainWindows?.toList().orEmpty(),
            )
    }
}
