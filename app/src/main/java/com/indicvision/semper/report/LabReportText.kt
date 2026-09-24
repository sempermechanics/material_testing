// Column widths are layout fractions, not tunable magic.
@file:Suppress("MagicNumber")

package com.indicvision.semper.report

/**
 * Fixed wording of the student lab report, kept beside [LabReport] so the
 * layout tests can read it without Android. English, like the rest of the
 * PDF. The sections, their order and their headings follow the handwritten
 * journal reports the lab asks for; only the instrument changes — a phone
 * camera with 2D DIC stands in for the extensometer or the dial gauge.
 */
object LabReportText {

    const val EXPERIMENT = "Experiment – ____"
    const val NAME = "Name"
    const val DATE = "Date"
    const val AIM = "Aim"
    const val THEORY = "Theory"
    const val OBSERVATIONS = "Observations"
    const val CALCULATION = "Calculation"
    const val RESULTS = "Results"
    const val CONCLUSIONS = "Conclusions"
    const val AXIS_STRAIN = "Strain (×10⁻³)"
    const val AXIS_STRESS = "Stress (MPa)"
    const val APPROXIMATE_NOTE =
        "Strain here comes from photographs (2D digital image correlation), not a contact extensometer, " +
            "so E is approximate — compare it with the value your lab provides."

    object Tensile {
        const val TITLE = "Measurement of Tensile Strains and Modulus of Elasticity"
        const val AIM =
            "To measure tensile strain by 2D digital image correlation (a phone camera) during a tension test " +
                "on a given tensile specimen, and to determine the value of modulus of elasticity."
        const val MATERIALS_HEADING = "Materials required"
        const val MATERIALS =
            "Hydraulic universal testing machine (UTM), phone camera with 2D DIC (in place of the extensometer), " +
                "speckle-painted mild steel test specimen, micrometer and vernier calipers."
        val THEORY = listOf(
            "When a bar or sheet of steel is pulled at its ends it experiences stress and strain. Strain is the " +
                "ratio of change in length to the original length, and stress is the load divided by the " +
                "cross-sectional area. Within the elastic limit stress and strain are proportional, forming a " +
                "straight-line graph (Hooke's law). The slope of this line gives the modulus of elasticity. If the " +
                "load is removed within this limit the material returns to its original shape — this is elastic " +
                "deformation.",
            "Beyond the elastic limit stress and strain no longer vary linearly, producing a curved graph. The " +
                "deformation in this region is plastic, meaning it is permanent and not recoverable when the load " +
                "is removed.",
        )
        const val FIGURE = "Specimen in the UTM — reference photo, analysed region outlined"

        const val TOTAL_LENGTH = "Total length of the test specimen (mm)"
        const val GAUGE_LENGTH = "Gauge length of specimen (mm)"
        const val DIAMETER = "Diameter of specimen (mm)"
        const val AREA = "Cross-sectional area A (mm²)"
        const val STRAIN_BY = "Strain measured by"
        fun strainBy(strainName: String) = "2D DIC, $strainName over the analysed region"
        const val FINAL_DIAMETER = "Final diameter (mm)"
        const val FINAL_GAUGE_LENGTH = "Final gauge length (mm)"

        val TABLE_HEADERS = listOf("S.No", "Load (kN)", "Extension (mm)", "Stress (MPa)", "Strain")
        val TABLE_WEIGHTS = listOf(0.10f, 0.20f, 0.22f, 0.22f, 0.26f)
        const val ELASTIC = "Elastic"
        const val PLASTIC = "Plastic"
        const val BREAK = "Break point"

        fun stressLine(loadKn: String, areaMm2: String, stressMPa: String) =
            "Stress = L / A = $loadKn × 10³ N / ($areaMm2 × 10⁻⁶ m²) = $stressMPa MPa"

        fun strainLine(strain: String) = "Strain = ΔL / L, read by DIC for row 1 = $strain"

        const val GRAPH_ELASTIC = "Stress vs strain — elastic region"
        const val GRAPH_FULL = "Stress–strain curve"

        const val RESULT_E = "Modulus of elasticity E"
        const val RESULT_PEAK = "Peak (ultimate) stress"
        const val NO_FIT = "not found — fewer than three straight-line points before the peak"
    }
}
