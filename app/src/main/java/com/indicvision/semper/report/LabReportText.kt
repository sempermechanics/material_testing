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
        fun dicGauge(axis: String) = "DIC gauge length along $axis (px)"
        fun dicGaugeValue(lengthPx: String) = "$lengthPx — between the ends of the analysed region"
        const val FINAL_DIAMETER = "Final diameter (mm)"
        const val FINAL_GAUGE_LENGTH = "Final gauge length (mm)"

        val TABLE_HEADERS = listOf("S.No", "Load (kN)", "Extension (px)", "Stress (MPa)", "Strain")
        val TABLE_WEIGHTS = listOf(0.10f, 0.20f, 0.22f, 0.22f, 0.26f)
        const val ELASTIC = "Elastic"
        const val PLASTIC = "Plastic"
        const val BREAK = "Break point"

        fun stressLine(loadKn: String, areaMm2: String, stressMPa: String) =
            "Stress = L / A = $loadKn × 10³ N / ($areaMm2 × 10⁻⁶ m²) = $stressMPa MPa"

        fun strainLine(strain: String) = "Strain = ΔL / L, read by DIC for row 1 = $strain"
        fun extensionLine(extensionPx: String, gaugePx: String) =
            "Extension ΔL for row 1 = $extensionPx px over a DIC gauge of $gaugePx px"

        const val GRAPH_ELASTIC = "Stress vs strain — elastic region"
        const val GRAPH_FULL = "Stress–strain curve"

        const val RESULT_E = "Modulus of elasticity E"
        const val RESULT_PEAK = "Peak (ultimate) stress"
        const val NO_FIT = "not found — fewer than three straight-line points before the peak"
    }

    object Bending {
        const val TITLE = "Measurement of Bending Moment and Deflection of Beam"
        const val AIM =
            "To measure deflections of the given beam by 2D digital image correlation (a phone camera) during " +
                "a bending test, and to use them to determine the modulus of elasticity of the material and " +
                "the bending stress."
        const val SETUP_HEADING = "Experimental setup"
        const val SETUP = "Beam, loading frame with hanger, phone camera with 2D DIC (in place of the dial gauge " +
            "indicator)."
        const val THEORY =
            "For a simply supported beam of span L carrying a load W at the middle of the span, the maximum " +
                "bending moment is M = W·L / 4 and the bending stress is σb = M·y / I. Here y is the distance of " +
                "the neutral axis from the surface, which for a rectangular beam is half the thickness, and I is " +
                "the second moment of area of the cross-section about the neutral axis, b·t³ / 12. The maximum " +
                "stress on the convex side of the beam is tensile. The deflection under the load, δ, gives the " +
                "modulus of elasticity as E = W·L³ / (48·δ·I); here it is measured from photographs instead of " +
                "a dial gauge."
        const val PROCEDURE_HEADING = "Experimental procedure"
        val PROCEDURE = listOf(
            "Adjust a convenient length L of the simply supported beam by adjusting the distance of the " +
                "flexible fixture from the fixed fixture.",
            "Fix the phone on a tripod facing the side of the beam at mid-span, and take the reference photo " +
                "with only the hanger on — the no-load reading.",
            "Place the hanger exactly at mid-span. Add the load in discrete steps and take a photo at each step.",
            "Record the photos for decreasing load in the same steps as used for increasing load.",
        )
        const val FIGURE = "Beam at mid-span — reference photo, thickness taps and deflection probe marked"

        const val SPAN = "Length of beam between simply supported ends L (mm)"
        const val WIDTH = "Width of beam b (mm)"
        const val THICKNESS = "Thickness of beam t (mm)"
        const val NO_LOAD = "No-load reading"
        const val NO_LOAD_VALUE = "reference photo = 0 mm"
        const val SCALE = "Scale from the thickness taps (mm per pixel)"

        val TABLE_HEADERS = listOf("Sr. No", "Load W (N)", "Deflection δ (mm)", "Bending stress σb (MPa)", "E (GPa)")
        val TABLE_WEIGHTS = listOf(0.10f, 0.19f, 0.23f, 0.30f, 0.18f)

        const val SIGMA_FORMULA = "σb = M · y / I"
        fun momentLine(loadN: String, spanM: String, momentNm: String) =
            "M = W·L / 4 = $loadN N × $spanM m / 4 = $momentNm N·m"
        fun yLine(yM: String) = "y = t / 2 = $yM m"
        fun inertiaLine(inertiaM4: String) = "I = b·t³ / 12 = $inertiaM4 m⁴"
        fun sigmaLine(sigmaMPa: String) = "σb = $sigmaMPa MPa"
        fun modulusLine(loadN: String, deflectionMm: String, modulusGPa: String) =
            "E = W·L³ / (48·δ·I) with W = $loadN N, δ = $deflectionMm mm: E = $modulusGPa GPa"
        const val NO_STEP_E = "E: the deflection in row 1 is under a pixel, too small to use."

        const val RESULT_MEAN = "Average value of E from calculations"
        const val RESULT_GRAPH = "Value of E from graph"
        const val NONE = "not available"
        const val GRAPH = "Load–deflection graph"
        const val AXIS_DEFLECTION = "Deflection δ (mm)"
        const val AXIS_LOAD = "Load W (N)"
        const val APPROXIMATE_NOTE =
            "Deflection here comes from photographs (2D digital image correlation), not a dial gauge, and " +
                "includes any movement of the whole beam; the value from the graph's slope is not affected by " +
                "that movement. Both are approximate — compare them with the value your lab provides."
    }
}
