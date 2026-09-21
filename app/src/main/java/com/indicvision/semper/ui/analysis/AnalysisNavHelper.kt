@file:Suppress("LongParameterList")

package com.indicvision.semper.ui.analysis

import android.app.Activity
import com.indicvision.semper.data.LicenseEntitlements
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.SkippedNode
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.navigation.AppIntents
import com.indicvision.semper.ui.viewer.ViewerArgs
import com.indicvision.semper.ui.viewer.ViewerSweepArgs
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

    fun openSeatRequired(host: Activity) {
        host.startActivity(AppIntents.seatRequired(host))
    }

    /**
     * Every reason a new analysis may not start, in one call.
     *
     * Returns false having already navigated to whichever gate applies. Both
     * checks are guarded by [AnalysisViewModel.wouldCreateNewSession], so a
     * **run already in flight, or a re-run of an existing session, always
     * passes** — losing a seat mid-analysis must never abort work.
     */
    suspend fun ensureCanStart(
        host: Activity,
        viewModel: AnalysisViewModel,
    ): Boolean = ensureSeat(host, viewModel) && ensureSessionQuota(host, viewModel)

    /**
     * Hard stop when this account holds no floating seat.
     *
     * A **separate** gate from the quota one rather than a widening of it: an
     * institution member is licensed, so [TokenStore.isSessionLimitReached] is
     * false for them by definition and they would otherwise pass every
     * existing check.
     *
     * Only a floating institution license can fail here. Assigned licenses,
     * individual licenses and demo accounts all return true and fall through
     * to the quota gate, which is what actually limits them.
     */
    @Suppress("ReturnCount") // early-outs for re-run / not-floating / blocked
    suspend fun ensureSeat(
        host: Activity,
        viewModel: AnalysisViewModel,
    ): Boolean {
        if (!viewModel.wouldCreateNewSession()) return true
        if (!LicenseEntitlements.seatRequiredToStart(host)) return true
        openSeatRequired(host)
        return false
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
        val mechanical = viewModel.mechanicalInputs(forSweep = sweep)
        host.startActivity(
            ViewerArgs(
                imgW = viewModel.realRefWidth,
                imgH = viewModel.realRefHeight,
                step = viewModel.lastStep,
                refName = viewModel.refName,
                refPath = viewModel.lastRefPath ?: "",
                batchDirPath = viewModel.lastBatchDirPath,
                frameNames = frameNames,
                stopCode = viewModel.lastStopCode,
                plannedFrames = viewModel.lastPlannedFrames,
                sessionId = viewModel.currentSessionId,
                sessionLocalId = viewModel.workingLocalId,
                subsetSize = subsetSize,
                strainWindow = strainWindow,
                engineStats = viewModel.engineStatsArray,
                roiX = viewModel.roiX,
                roiY = viewModel.roiY,
                roiW = viewModel.roiW,
                roiH = viewModel.roiH,
                sweep = if (sweep) {
                    ViewerSweepArgs(
                        subsets = plan.map { it.subset },
                        steps = plan.map { it.step },
                        strainWindows = plan.map { it.strainWindow },
                        lineCutHorizontal = viewModel.lineCutHorizontal,
                        skippedJson = SkippedNode.encodeJson(viewModel.sweepSkippedNodes),
                    )
                } else {
                    null
                },
                defPath = viewModel.lastDefPath,
                defFilePaths = viewModel.defFilePaths,
                testType = mechanical.testType,
                crossSectionMm2 = mechanical.crossSectionMm2,
                loadAxisX = mechanical.loadAxisX,
                loadsN = mechanical.loadsN.take(frameNames.size).toFloatArray(),
            ).toIntent(host),
        )
    }
}
