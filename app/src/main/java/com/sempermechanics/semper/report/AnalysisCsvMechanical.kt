package com.sempermechanics.semper.report

import com.sempermechanics.semper.data.mechanical.CurveCorrection
import java.io.Writer
import java.util.Locale

/**
 * The mechanical test's part of [AnalysisCsvWriter]'s file (material_testing):
 * the preamble's test block (type, stress model, dimensions, load axis, the
 * bending load point or the tensile correction) and the `# mechanical_results`
 * trailer the lab report's Results quote.
 */
internal object AnalysisCsvMechanical {

    /** The preamble's test block, after the ROI rows; nothing for a plain DIC session. */
    fun writePreamble(w: Writer, metadata: AnalysisCsvWriter.Metadata) {
        if (metadata.testType.isNotBlank()) {
            val model = metadata.stressModel
            w.append("# test_type,").append(AnalysisCsvWriter.escape(metadata.testType)).append('\n')
            w.append("# stress_model,").append(model.wireName).append('\n')
            // Cross-section is written even at 0, as the first version-2 files
            // did; the bending dimensions only once entered.
            model.dimensions.forEach { (dimension, value) ->
                if (dimension == StressStrain.Dimension.CROSS_SECTION || value > 0f) {
                    w.append("# ").append(dimension.csvKey).append(',')
                        .append(String.format(Locale.US, "%.4f", value)).append('\n')
                }
            }
            w.append("# load_axis,").append(if (metadata.loadAxisX) "x" else "y").append('\n')
            w.append("# load_unit,N\n")
            (model as? StressStrain.Model.Flexural)?.probe?.let { writeLoadPoint(w, it) }
            (model as? StressStrain.Model.Axial)?.correction?.takeUnless { it.isNone }?.let { writeCorrection(w, it) }
        }
    }

    /**
     * Tensile's hand-entered scale and bias. `stress_MPa` and the mechanical
     * results carry them; each point's field strains stay as the camera measured.
     */
    private fun writeCorrection(w: Writer, c: CurveCorrection) {
        w.append("# strain_scale,").append(num(c.strainScale)).append('\n')
        w.append("# strain_bias_millistrain,").append(num(c.strainBiasMilli)).append('\n')
        w.append("# stress_scale,").append(num(c.stressScale)).append('\n')
        w.append("# stress_bias_MPa,").append(num(c.stressBiasMPa)).append('\n')
    }

    private fun num(value: Float): String = String.format(Locale.US, "%.6f", value)

    /** Bending's tapped edges (reference px) and the scale they give. */
    private fun writeLoadPoint(w: Writer, probe: BeamDeflection.Probe) {
        val taps = probe.taps
        w.append("# load_point_top_px,").append(px(taps.topX)).append(',').append(px(taps.topY)).append('\n')
        w.append("# load_point_bottom_px,").append(px(taps.bottomX)).append(',').append(px(taps.bottomY)).append('\n')
        w.append("# mm_per_px,").append(String.format(Locale.US, "%.6f", probe.mmPerPx)).append('\n')
    }

    private fun px(value: Float): String = String.format(Locale.US, "%.2f", value)

    /**
     * The trailer: what the lab report's Results section quotes. Tensile gets
     * Young's modulus from [ElasticModulus]; an empty value means no straight
     * run was found. Bending with a load point gets the lab's observation
     * table and both of its E values ([BeamDeflection]). 1-based frame
     * numbers, like the viewer and the report.
     */
    fun writeResults(w: Writer, curve: StressStrain.Curve) {
        when {
            curve.model is StressStrain.Model.Axial -> writeTensileResults(w, curve)
            curve.model.plotsLoadDeflection -> BeamDeflection.summarize(curve)?.let { writeBendingResults(w, it) }
        }
    }

    private fun writeTensileResults(w: Writer, curve: StressStrain.Curve) {
        val fit = ElasticModulus.fit(curve)
        w.append("# mechanical_results\n")
        w.append("# elastic_modulus_gpa,")
        if (fit != null) {
            w.append(String.format(Locale.US, "%.4f", fit.modulusGPa)).append('\n')
            w.append("# elastic_fit_frames,${fit.firstFrame + 1},${fit.lastFrame + 1}\n")
            w.append("# elastic_fit_r2,").append(String.format(Locale.US, "%.6f", fit.r2)).append('\n')
        } else {
            w.append('\n')
        }
    }

    private fun writeBendingResults(w: Writer, summary: BeamDeflection.Summary) {
        w.append("# mechanical_results\n")
        // Only when set, so a session without one writes the same bytes as before.
        summary.correction.takeUnless { it.isNone }?.let { c ->
            w.append("# deflection_correction_scale,").append(String.format(Locale.US, "%.4f", c.scale)).append('\n')
            w.append("# deflection_correction_bias_mm,").append(String.format(Locale.US, "%.4f", c.biasMm))
                .append('\n')
        }
        w.append("# bending_step,frame,load_N,deflection_mm,flexural_stress_MPa,e_GPa\n")
        summary.steps.forEach { step ->
            w.append("# bending_step,${step.frame + 1},")
                .append(String.format(Locale.US, "%.4f", step.loadN)).append(',')
                .append(String.format(Locale.US, "%.5f", step.deflectionMm)).append(',')
                .append(String.format(Locale.US, "%.4f", step.stressMPa)).append(',')
                .append(gpaOrEmpty(step.modulusGPa)).append('\n')
        }
        w.append("# e_mean_gpa,").append(gpaOrEmpty(summary.meanModulusGPa)).append('\n')
        summary.slope?.let { line ->
            w.append("# load_deflection_slope_N_per_mm,").append(String.format(Locale.US, "%.4f", line.slope))
                .append('\n')
            w.append("# load_deflection_r2,").append(String.format(Locale.US, "%.6f", line.r2)).append('\n')
        }
        w.append("# e_slope_gpa,").append(gpaOrEmpty(summary.slopeModulusGPa)).append('\n')
    }

    private fun gpaOrEmpty(value: Float?): String = value?.let { String.format(Locale.US, "%.4f", it) }.orEmpty()
}
