package com.rafad.indicvisiondic.ui.home

import android.content.Context
import android.content.Intent
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.data.SessionRecord
import com.rafad.indicvisiondic.ui.analysis.VsgLatticeActivity
import com.rafad.indicvisiondic.ui.viewer.ResultViewerActivity

/**
 * Packs a [SessionRecord] into the Intent that opens either the result viewer
 * or the VSG lattice (sweeps). Callers still gate on [SessionRecord.hasLocalData].
 */
object SessionOpenHelper {

    fun intentFor(context: Context, session: SessionRecord): Intent {
        // A sweep session opens on the interactive lattice, which forwards these
        // same extras to the result viewer when a node is tapped.
        val target = if (session.isSweep) {
            VsgLatticeActivity::class.java
        } else {
            ResultViewerActivity::class.java
        }
        return Intent(context, target).apply {
            putExtra(DicKeys.IMG_W, session.imgW)
            putExtra(DicKeys.IMG_H, session.imgH)
            putExtra(DicKeys.STEP, session.step)
            putExtra(DicKeys.REF_NAME, session.refName)
            putExtra(DicKeys.REF_PATH, session.refPath)
            putExtra(DicKeys.BATCH_DIR_PATH, session.sessionDir)
            // A sweep names its frames after the combination behind them, and
            // needs each frame's own settings to render and describe it.
            val frameNames = if (session.isSweep) session.sweepLabels else session.defNames
            putStringArrayListExtra(DicKeys.DEF_FILE_NAMES, ArrayList(frameNames))
            if (session.isSweep) {
                putExtra(DicKeys.SWEEP_SUBSETS, session.sweepSubsets.toIntArray())
                putExtra(DicKeys.SWEEP_STEPS, session.sweepSteps.toIntArray())
                putExtra(DicKeys.SWEEP_STRAIN_WINS, session.sweepStrainWindows.toIntArray())
                putExtra(DicKeys.LINE_CUT_HORIZONTAL, session.lineCutHorizontal)
                putExtra(DicKeys.SWEEP_SKIP_SUBSETS, session.sweepSkipSubsets.toIntArray())
                putExtra(DicKeys.SWEEP_SKIP_STEPS, session.sweepSkipSteps.toIntArray())
                putExtra(DicKeys.SWEEP_SKIP_STRAIN_WINS, session.sweepSkipStrainWindows.toIntArray())
            }
            putExtra(DicKeys.SESSION_ID, session.id)
            putExtra(DicKeys.SESSION_LOCAL_ID, session.id)
            putExtra(DicKeys.SUBSET_SIZE, session.subset)
            putExtra(DicKeys.STRAIN_WINDOW, session.strainWindow)
            putExtra(DicKeys.STRAIN_METHOD, "VSG")
            putExtra(DicKeys.ENGINE_STATS, session.engineStats.toFloatArray())
            putExtra(DicKeys.ROI_X, session.roiX)
            putExtra(DicKeys.ROI_Y, session.roiY)
            putExtra(DicKeys.ROI_W, session.roiW)
            putExtra(DicKeys.ROI_H, session.roiH)
        }
    }
}
