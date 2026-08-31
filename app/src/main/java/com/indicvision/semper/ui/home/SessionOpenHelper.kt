package com.indicvision.semper.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SkippedNode
import com.indicvision.semper.ui.analysis.VsgLatticeActivity
import com.indicvision.semper.ui.viewer.ResultViewerActivity

/**
 * Packs a [SessionRecord] into the Intent that opens either the result viewer
 * or the VSG lattice (sweeps).
 */
object SessionOpenHelper {

    /**
     * Opens [session], or says why it cannot be opened when its frames are no
     * longer on this phone — a session deleted locally but kept in the cloud
     * still shows on both the Home list and the settings page.
     */
    fun openOrExplain(activity: Activity, session: SessionRecord) {
        if (!session.hasLocalData()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.session_data_gone_title)
                .setMessage(R.string.session_data_gone_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        activity.startActivity(intentFor(activity, session))
    }

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
                putExtra(DicKeys.SWEEP_SKIPPED, SkippedNode.encodeJson(session.resolvedSkipNodes()))
            }
            putExtra(DicKeys.STOP_CODE, session.stopCode)
            putExtra(DicKeys.PLANNED_FRAMES, session.plannedFrameCount)
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
