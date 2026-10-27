package com.sempermechanics.semper.ui.analysis.rerun

import android.content.Intent
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.mechanical.TypedLoads
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import com.sempermechanics.semper.ui.analysis.wizard.editLab
import com.sempermechanics.semper.ui.analysis.wizard.mechanicalInputsReady
import com.sempermechanics.semper.ui.analysis.wizard.withMachineLoadsMatched
import com.sempermechanics.semper.ui.analysis.wizard.withTypedLoadsKg
import java.math.BigDecimal
import java.math.MathContext

/**
 * The lab test a Home Re-run copies along with the photos (TD-220): the test
 * type, the dimensions and beam-edge taps, and bending's typed hanger masses.
 * Home has the record in hand, so it rides the wizard's Intent
 * ([putOn] / [from]) and is in the view model before the load card is built
 * ([applyTo]); the typed masses go on the copied frames once they are in
 * ([applyLoadsTo]). A machine's load log is not kept with the analysis, so a
 * test that read one asks for it again on step 1.
 *
 * The taps stay valid: a Re-run's reference is the saved one at its own size.
 *
 * @property typedLoadsN the saved loads, one per frame in run order, when
 *   they were typed on the phone; empty for a load log or a sweep.
 */
internal data class LabRerun(
    val testType: TestType,
    val crossSectionMm2: Float = 0f,
    val loadAxisX: Boolean = true,
    val geometry: SpecimenGeometry = SpecimenGeometry.NONE,
    val typedLoadsN: List<Float> = emptyList(),
) {
    /** The Re-run's extras, beside `RERUN_SESSION_ID`; [IntentKeys.TEST_TYPE] is the one the wizard reads anyway. */
    fun putOn(intent: Intent): Intent = intent
        .putExtra(IntentKeys.TEST_TYPE, testType.wireName)
        .putExtra(IntentKeys.CROSS_SECTION_MM2, crossSectionMm2)
        .putExtra(IntentKeys.LOAD_AXIS_X, loadAxisX)
        .putExtra(IntentKeys.SPECIMEN_GEOMETRY, geometry.toArray())
        .putExtra(IntentKeys.LOADS_N, typedLoadsN.toFloatArray())

    /** The test and its dimensions into [viewModel], before the load card reads them. */
    fun applyTo(viewModel: AnalysisViewModel) {
        viewModel.editLab {
            it.copy(testType = testType, crossSectionMm2 = crossSectionMm2, loadAxisX = loadAxisX, geometry = geometry)
        }
    }

    /**
     * The typed masses onto the frames the Re-run copied, when there is one
     * for each, then the loads matched again; and step 1 when the test still
     * needs anything there (a load log to import), else the page the Re-run
     * chose.
     */
    fun applyLoadsTo(viewModel: AnalysisViewModel) = viewModel.edit { inputs ->
        val typed = if (typedLoadsN.isNotEmpty() && typedLoadsN.size == inputs.deformedFrames.size) {
            inputs.withTypedLoadsKg(typedLoadsN.map(::typedKg))
        } else {
            inputs
        }
        val matched = typed.withMachineLoadsMatched()
        if (matched.mechanicalInputsReady()) matched else matched.copy(step = WizardStep.IMAGES)
    }

    companion object {
        /** Significant digits a typed mass comes back with: more than a box is typed to, fewer than a Float holds. */
        private const val TYPED_DIGITS = 6

        /** [record]'s lab test, or null for a plain DIC one (or a type this build does not offer). */
        fun of(record: SessionRecord): LabRerun? {
            val type = TestType.fromWire(record.testType)?.takeIf { it.hasMachineLoad } ?: return null
            val typed = record.loadSource == TypedLoads.SOURCE && record.hasMachineLoads
            return LabRerun(
                testType = type,
                crossSectionMm2 = record.crossSectionMm2,
                loadAxisX = record.loadAxisX,
                geometry = record.geometry,
                typedLoadsN = if (typed) record.loadsN else emptyList(),
            )
        }

        /** What [putOn] wrote, or null when the Intent carries no lab test. */
        fun from(intent: Intent): LabRerun? {
            val type = TestType.fromWire(intent.getStringExtra(IntentKeys.TEST_TYPE))
                ?.takeIf { it.hasMachineLoad }
                ?: return null
            return LabRerun(
                testType = type,
                crossSectionMm2 = intent.getFloatExtra(IntentKeys.CROSS_SECTION_MM2, 0f),
                loadAxisX = intent.getBooleanExtra(IntentKeys.LOAD_AXIS_X, true),
                geometry = SpecimenGeometry.fromArray(intent.getFloatArrayExtra(IntentKeys.SPECIMEN_GEOMETRY)),
                typedLoadsN = intent.getFloatArrayExtra(IntentKeys.LOADS_N)?.toList().orEmpty(),
            )
        }

        /**
         * A saved load (N) as the mass the student typed (kg): W / g, rounded
         * to [TYPED_DIGITS] significant digits so that a typed 0.5 kg comes
         * back as 0.5, not 0.49999997, and so weighs exactly what it did.
         * Null for a frame that had none.
         */
        fun typedKg(loadN: Float): Float? = loadN.takeIf { it.isFinite() }?.let {
            BigDecimal(TypedLoads.kg(it).toDouble()).round(MathContext(TYPED_DIGITS)).toFloat()
        }
    }
}
