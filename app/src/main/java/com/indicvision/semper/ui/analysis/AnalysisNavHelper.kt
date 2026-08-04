@file:Suppress("LongParameterList")

package com.indicvision.semper.ui.analysis

import android.app.Activity
import android.content.Intent
import com.indicvision.semper.DicKeys
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.navigation.AppIntents
import com.indicvision.semper.ui.viewer.ResultViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Navigation helpers extracted from [StaticAnalysisActivity] so limit-gate and
 * result-open flows do not keep growing the Activity.
 */
object AnalysisNavHelper {

    fun openSessionLimit(host: Activity) {
        host.startActivity(AppIntents.sessionLimit(host))
    }

    /**
     * Hard stop for a new session when the quota is **known and full**. Returns
     * false after navigating to the limit screen; re-runs of an existing session
     * still pass, and an unknown quota does not block — analysis is on-device and
     * only its upload is gated (see [com.indicvision.semper.data.CloudSync]).
     * Reads the local session index off the main thread.
     */
    @Suppress("ReturnCount") // early-outs for re-run / under-quota / blocked
    suspend fun ensureSessionQuota(
        host: Activity,
        viewModel: AnalysisViewModel,
    ): Boolean {
        if (!viewModel.wouldCreateNewSession()) return true
        val localCount = withContext(Dispatchers.IO) {
            SessionStore.list(host).size
        }
        TokenStore.refreshSessionLimit(host, localCount)
        if (!TokenStore.isSessionLimitReached(host)) return true
        openSessionLimit(host)
        return false
    }

    fun openResults(
        host: Activity,
        viewModel: AnalysisViewModel,
        sweep: Boolean,
        frameNames: ArrayList<String>,
        subsetSize: Int,
        strainWindow: Int,
    ) {
        val plan = viewModel.sweepPlan
        val target = if (sweep) VsgLatticeActivity::class.java else ResultViewerActivity::class.java
        val intent = Intent(host, target).apply {
            putExtra(DicKeys.IMG_W, viewModel.realRefWidth)
            putExtra(DicKeys.IMG_H, viewModel.realRefHeight)
            putExtra(DicKeys.STEP, viewModel.lastStep)
            putExtra(DicKeys.REF_NAME, viewModel.refName)
            putExtra(DicKeys.REF_PATH, viewModel.lastRefPath ?: "")
            putExtra(DicKeys.DEF_PATH, viewModel.lastDefPath)
            putExtra(DicKeys.BATCH_DIR_PATH, viewModel.lastBatchDirPath)
            putStringArrayListExtra(DicKeys.DEF_FILE_NAMES, frameNames)
            putStringArrayListExtra(DicKeys.DEF_FILE_PATHS, ArrayList(viewModel.defFilePaths))
            if (sweep) {
                putExtra(DicKeys.SWEEP_SUBSETS, plan.map { it.subset }.toIntArray())
                putExtra(DicKeys.SWEEP_STEPS, plan.map { it.step }.toIntArray())
                putExtra(DicKeys.SWEEP_STRAIN_WINS, plan.map { it.strainWindow }.toIntArray())
                putExtra(DicKeys.LINE_CUT_HORIZONTAL, viewModel.lineCutHorizontal)
                val skipped = viewModel.sweepSkipped
                putExtra(DicKeys.SWEEP_SKIP_SUBSETS, skipped.map { it.subset }.toIntArray())
                putExtra(DicKeys.SWEEP_SKIP_STEPS, skipped.map { it.step }.toIntArray())
                putExtra(DicKeys.SWEEP_SKIP_STRAIN_WINS, skipped.map { it.strainWindow }.toIntArray())
                putExtra(DicKeys.SWEEP_SKIP_CODES, viewModel.sweepSkippedCodes.toIntArray())
            }
            putExtra(DicKeys.STOP_CODE, viewModel.lastStopCode)
            putExtra(DicKeys.PLANNED_FRAMES, viewModel.lastPlannedFrames)
            putExtra(DicKeys.SESSION_ID, viewModel.currentSessionId)
            putExtra(DicKeys.SESSION_LOCAL_ID, viewModel.workingLocalId)
            putExtra(DicKeys.SUBSET_SIZE, subsetSize)
            putExtra(DicKeys.STRAIN_WINDOW, strainWindow)
            putExtra(DicKeys.STRAIN_METHOD, "VSG")
            putExtra(DicKeys.ENGINE_STATS, viewModel.engineStatsArray)
            putExtra(DicKeys.ROI_X, viewModel.roiX)
            putExtra(DicKeys.ROI_Y, viewModel.roiY)
            putExtra(DicKeys.ROI_W, viewModel.roiW)
            putExtra(DicKeys.ROI_H, viewModel.roiH)
        }
        host.startActivity(intent)
    }
}
