package com.sempermechanics.semper.data.mechanical

import com.sempermechanics.semper.report.StressStrain

/**
 * A saved session's lab test as one value: the type and the fields its
 * stress model is made of. A blank [testType] is a plain 2D DIC session,
 * which has no model ([stressModel] is null), so a reader asks this rather
 * than checking the type itself before `StressStrain.Model.of` (which reads
 * blank as axial).
 */
data class LabSession(
    val testType: String = "",
    val crossSectionMm2: Float = 0f,
    val loadAxisX: Boolean = true,
    val geometry: SpecimenGeometry = SpecimenGeometry.NONE,
    val curveCorrection: CurveCorrection = CurveCorrection.NONE,
) {
    /** A tensile or bending session; a type this build no longer offers still counts, and reads as axial. */
    val isLabTest: Boolean get() = testType.isNotBlank()

    /** How this session's loads become stress, or null for a plain DIC session. */
    fun stressModel(): StressStrain.Model? = if (isLabTest) stressModelOrAxial() else null

    /**
     * How this session's loads become stress, a plain DIC session's read as
     * axial (`StressStrain.Model.of`'s blank): for the readers that want a
     * model whatever the session, the viewer's curve and the CSV's stress
     * column, which ask [isLabTest] for whether to show it.
     */
    fun stressModelOrAxial(): StressStrain.Model =
        StressStrain.Model.of(testType, crossSectionMm2, loadAxisX, geometry, curveCorrection)

    companion object {
        val NONE = LabSession()
    }
}
