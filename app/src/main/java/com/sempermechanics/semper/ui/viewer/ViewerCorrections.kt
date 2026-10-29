// The Results corrections on the result viewer: the session's geometry and
// curve correction as Results has them, the stress model they make, and the
// Adjust curve / Adjust deflection writes. Split out of ResultViewerActivity.
package com.sempermechanics.semper.ui.viewer

import com.sempermechanics.semper.data.mechanical.CurveCorrection
import com.sempermechanics.semper.data.mechanical.LabSession
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.setCurveCorrection
import com.sempermechanics.semper.data.session.setDeflectionCorrection
import com.sempermechanics.semper.report.BeamDeflection
import com.sempermechanics.semper.report.StressStrain

/** The session's geometry, with the deflection correction set in Results when there is one. */
internal val ResultViewerActivity.geometry: SpecimenGeometry
    get() = viewModel.deflectionCorrection?.let(args.labSession.geometry::withCorrection) ?: args.labSession.geometry

/** The tensile scale and bias: one typed on this screen, else the session's. */
internal val ResultViewerActivity.curveCorrection: CurveCorrection
    get() = viewModel.curveCorrection ?: args.labSession.curveCorrection

/**
 * The session's lab test with Results' corrections: what the curve, share
 * sheet, report and CSV read ([ResultViewerActivity.labSession]).
 */
internal fun ResultViewerActivity.correctedLabSession(): LabSession =
    args.labSession.copy(geometry = geometry, curveCorrection = curveCorrection)

/**
 * Results' Adjust curve: redraws the cached curve under [correction]
 * without decoding the frames again, on every Results surface on screen,
 * and saves it on the session so the next open, the CSV and the report
 * read it. A backed-up session's cloud copy gets the new metadata too.
 */
internal fun ResultViewerActivity.applyCurveCorrection(correction: CurveCorrection) {
    viewModel.curveCorrection = correction
    viewModel.stressStrain = viewModel.stressStrain?.let { StressStrain.recorrect(it, correction) }
    stressStrain.redraw()
    viewModel.saveOnSession(args.sessionLocalId) { appContext, id ->
        SessionStore.setCurveCorrection(appContext, id, correction)
    }
}

/**
 * Results' Adjust deflection: [correction] becomes the session's. The
 * cached curve is re-mapped rather than rebuilt, the Results on screen
 * redraw, and the share sheet, report and CSV read it through [geometry].
 * Saved to the session so a reopen shows it too.
 */
internal fun ResultViewerActivity.applyDeflectionCorrection(correction: BeamDeflection.Correction) {
    viewModel.deflectionCorrection = correction
    viewModel.stressStrain = viewModel.stressStrain?.let { BeamDeflection.Correction.recorrect(it, stressModel) }
    stressStrain.redraw()
    viewModel.saveOnSession(args.sessionId) { appContext, id ->
        SessionStore.setDeflectionCorrection(appContext, id, correction)
    }
}
