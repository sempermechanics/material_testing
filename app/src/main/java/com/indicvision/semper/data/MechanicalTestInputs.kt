package com.indicvision.semper.data

/**
 * What the user told the wizard about the mechanical test, captured into a
 * [SessionRecord] at commit. Every field has an "absent" value so a session
 * with no test type (or a sweep, which records the type but no loads)
 * stores the same shape as one with everything.
 *
 * [loadsN] is one signed load per deformed frame, in newtons, exactly as the
 * machine reported it — a compression rig that logs negative force stays
 * negative, so stress and the DIC's own strain sign agree or visibly disagree.
 */
data class MechanicalTestInputs(
    val testType: String = "",
    val crossSectionMm2: Float = 0f,
    val loadAxisX: Boolean = true,
    val geometry: SpecimenGeometry = SpecimenGeometry.NONE,
    val loadsN: List<Float> = emptyList(),
    val loadSource: String = "",
    val loadMapping: String = "",
) {
    companion object {
        val NONE = MechanicalTestInputs()
    }
}
