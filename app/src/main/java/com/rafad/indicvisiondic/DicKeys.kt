package com.rafad.indicvisiondic

/**
 * Keys shared across component boundaries — Intent extras and WorkManager
 * [androidx.work.Data]. Centralized so a typo becomes a compile error instead
 * of a silent fallback to a default value.
 *
 * Same-file keys (e.g. savedInstanceState) intentionally stay local.
 */
object DicKeys {
    // ── Auth  session hand-off (AuthActivity / SplashActivity / PendingApproval)
    const val USER_ID = "USER_ID"
    const val USER_EMAIL = "USER_EMAIL"

    // ── HomeActivity → StaticAnalysisActivity (media picker hand-off)
    const val PICKED_REF_URI = "PICKED_REF_URI"
    const val PICKED_VIDEO_URI = "PICKED_VIDEO_URI"

    // ── StaticAnalysisActivity  RoiDrawActivity
    const val IMAGE_FILE_PATH = "IMAGE_FILE_PATH"
    const val MASK_FILE_PATH = "MASK_FILE_PATH"
    const val IMAGE_WIDTH = "IMAGE_WIDTH"
    const val IMAGE_HEIGHT = "IMAGE_HEIGHT"
    const val DRAW_MODE = "DRAW_MODE"
    const val ROI_L = "ROI_L"
    const val ROI_R = "ROI_R"
    const val ROI_T = "ROI_T"
    const val ROI_B = "ROI_B"

    // ── StaticAnalysisActivity  ResultViewerActivity
    const val SESSION_ID = "SESSION_ID"
    const val REF_NAME = "REF_NAME"
    const val REF_PATH = "REF_PATH"
    const val DEF_PATH = "DEF_PATH"
    const val BATCH_DIR_PATH = "BATCH_DIR_PATH"
    const val DEF_FILE_NAMES = "DEF_FILE_NAMES"

    /** Full paths to the raw deformed images (transient cache copies) — best-effort
     *  source for the export's "raw photos" folder; absent when reopened from Home. */
    const val DEF_FILE_PATHS = "DEF_FILE_PATHS"
    const val SUBSET_SIZE = "SUBSET_SIZE"
    const val STRAIN_WINDOW = "STRAIN_WINDOW"
    const val STRAIN_METHOD = "STRAIN_METHOD"
    const val ENGINE_STATS = "ENGINE_STATS"
    const val ROUTING_ERROR = "ROUTING_ERROR"
    const val POINTS_CONVERGED = "POINTS_CONVERGED"
    const val EXEC_TIME = "EXEC_TIME"
    const val AVG_ITERS = "AVG_ITERS"

    // ── AnalysisViewModel  DicUploadWorker (WorkManager Data)
    const val SESSION_LOCAL_ID = "SESSION_LOCAL_ID"
    const val STEP = "STEP"
    const val SUBSET = "SUBSET"
    const val STRAIN_WIN = "STRAIN_WIN"
    const val IMG_W = "IMG_W"
    const val IMG_H = "IMG_H"
    const val ROI_X = "ROI_X"
    const val ROI_Y = "ROI_Y"
    const val ROI_W = "ROI_W"
    const val ROI_H = "ROI_H"
    const val FRAME_NAME = "FRAME_NAME"
    const val DAT_PATH = "DAT_PATH"

    // ── Parameter sweep (VsgStudy) → ResultViewerActivity
    // A sweep varies the settings instead of the image, so each frame of the
    // result carries its own subset/step/strain window. Absent for an ordinary
    // analysis, which is what tells the viewer it is not looking at a sweep.

    /** Per-frame subset sizes, index-aligned with the frames. */
    const val SWEEP_SUBSETS = "SWEEP_SUBSETS"

    /** Per-frame step sizes. Also drives rendering, which is step-dependent. */
    const val SWEEP_STEPS = "SWEEP_STEPS"

    /** Per-frame strain windows. */
    const val SWEEP_STRAIN_WINS = "SWEEP_STRAIN_WINS"

    /** True when the study's line cut runs along x; false for along y. */
    const val LINE_CUT_HORIZONTAL = "LINE_CUT_HORIZONTAL"

    // Sweep combinations the engine could not solve, for the lattice staging
    // screen (VsgLatticeActivity). Index-aligned with each other.
    const val SWEEP_SKIP_SUBSETS = "SWEEP_SKIP_SUBSETS"
    const val SWEEP_SKIP_STEPS = "SWEEP_SKIP_STEPS"
    const val SWEEP_SKIP_STRAIN_WINS = "SWEEP_SKIP_STRAIN_WINS"

    /** Frame the result viewer opens on — a lattice node tap picks one. */
    const val START_FRAME = "START_FRAME"
}
