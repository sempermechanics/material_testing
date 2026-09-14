package com.indicvision.semper.ui.viewer

import android.content.Context
import android.content.Intent
import com.indicvision.semper.DicKeys
import com.indicvision.semper.ui.analysis.VsgLatticeActivity

/**
 * Per-frame settings only a parameter sweep carries.
 *
 * Its presence is also what picks the destination: a sweep opens on the
 * interactive lattice, which forwards the whole extras bundle on to the result
 * viewer when a node is tapped.
 */
data class ViewerSweepArgs(
    val subsets: List<Int>,
    val steps: List<Int>,
    val strainWindows: List<Int>,
    val lineCutHorizontal: Boolean,
    val skippedJson: String,
)

/**
 * Everything a viewer launch puts on its Intent, in one place.
 *
 * Two callers open the viewer — `SessionOpenHelper` from the Home list and a
 * finished run from `AnalysisNavHelper` — and until this existed each packed
 * the same twenty-odd [DicKeys] itself. A key added to one and missed on the
 * other produced a viewer that worked from one entry point and not the other,
 * with nothing to fail: a missing extra reads as a default.
 *
 * Deliberately **not** a `Parcelable` under a single extra. That would also
 * change the twenty-odd reads in `ResultViewerActivity` and
 * `VsgLatticeActivity`, and break any Intent already in the back stack across
 * an update. This is the write side alone; the wire format is untouched.
 *
 * Holds an array, so do not compare instances — the generated `equals` is
 * reference-based for [engineStats].
 */
data class ViewerArgs(
    val imgW: Int,
    val imgH: Int,
    val step: Int,
    val refName: String,
    val refPath: String,
    val batchDirPath: String?,
    val frameNames: List<String>,
    val stopCode: Int,
    val plannedFrames: Int,
    val sessionId: String?,
    val sessionLocalId: String?,
    val subsetSize: Int,
    val strainWindow: Int,
    val engineStats: FloatArray?,
    val roiX: Int,
    val roiY: Int,
    val roiW: Int,
    val roiH: Int,
    val sweep: ViewerSweepArgs? = null,
    // The just-analysed run's temp paths. Home omits both: its frames are the
    // originals persisted under the session dir, which the viewer prefers
    // anyway, and an absent extra and an empty one read the same there.
    val defPath: String? = null,
    val defFilePaths: List<String> = emptyList(),
) {

    fun toIntent(context: Context): Intent {
        val target = if (sweep != null) {
            VsgLatticeActivity::class.java
        } else {
            ResultViewerActivity::class.java
        }
        return Intent(context, target).apply {
            putExtra(DicKeys.IMG_W, imgW)
            putExtra(DicKeys.IMG_H, imgH)
            putExtra(DicKeys.STEP, step)
            putExtra(DicKeys.REF_NAME, refName)
            putExtra(DicKeys.REF_PATH, refPath)
            defPath?.let { putExtra(DicKeys.DEF_PATH, it) }
            putExtra(DicKeys.BATCH_DIR_PATH, batchDirPath)
            putStringArrayListExtra(DicKeys.DEF_FILE_NAMES, ArrayList(frameNames))
            if (defFilePaths.isNotEmpty()) {
                putStringArrayListExtra(DicKeys.DEF_FILE_PATHS, ArrayList(defFilePaths))
            }
            if (sweep != null) {
                putExtra(DicKeys.SWEEP_SUBSETS, sweep.subsets.toIntArray())
                putExtra(DicKeys.SWEEP_STEPS, sweep.steps.toIntArray())
                putExtra(DicKeys.SWEEP_STRAIN_WINS, sweep.strainWindows.toIntArray())
                putExtra(DicKeys.LINE_CUT_HORIZONTAL, sweep.lineCutHorizontal)
                putExtra(DicKeys.SWEEP_SKIPPED, sweep.skippedJson)
            }
            putExtra(DicKeys.STOP_CODE, stopCode)
            putExtra(DicKeys.PLANNED_FRAMES, plannedFrames)
            putExtra(DicKeys.SESSION_ID, sessionId)
            putExtra(DicKeys.SESSION_LOCAL_ID, sessionLocalId)
            putExtra(DicKeys.SUBSET_SIZE, subsetSize)
            putExtra(DicKeys.STRAIN_WINDOW, strainWindow)
            putExtra(DicKeys.STRAIN_METHOD, "VSG")
            putExtra(DicKeys.ENGINE_STATS, engineStats)
            putExtra(DicKeys.ROI_X, roiX)
            putExtra(DicKeys.ROI_Y, roiY)
            putExtra(DicKeys.ROI_W, roiW)
            putExtra(DicKeys.ROI_H, roiH)
        }
    }
}
