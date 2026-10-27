package com.sempermechanics.semper.navigation

/**
 * Keys shared across component boundaries — Intent extras and WorkManager
 * [androidx.work.Data]. Centralized so a typo becomes a compile error instead
 * of a silent fallback to a default value.
 *
 * Same-file keys (e.g. savedInstanceState) intentionally stay local.
 */
object IntentKeys {
    // Auth routing (e.g. PendingApproval → AuthActivity) uses [ROUTING_ERROR]
    // below; identity lives in Firebase Auth / AccountCache, not Intent extras.

    // ── HomeActivity → StaticAnalysisActivity (media picker hand-off)
    const val PICKED_REF_URI = "PICKED_REF_URI"
    const val PICKED_VIDEO_URI = "PICKED_VIDEO_URI"

    /** Deformed-frame URI strings; consumed once on wizard open. */
    const val PICKED_DEF_URIS = "PICKED_DEF_URIS"

    /**
     * [com.sempermechanics.semper.data.mechanical.TestType.wireName] chosen on Home. Left on
     * the wizard's Intent (not consumed) so a recreation after process death
     * still knows which test it is building; the viewer carries it too.
     */
    const val TEST_TYPE = "TEST_TYPE"

    // ── ExamplesActivity's Try it → StaticAnalysisActivity (ADR-031)
    /**
     * The bundled example set the wizard was opened on. Left on the Intent,
     * like [TEST_TYPE]: it tells the first open to take the set's dimensions.
     */
    const val EXAMPLE_ID = "EXAMPLE_ID"

    /** IntArray(4): the example's ROI, x, y, width, height in reference pixels; consumed once. */
    const val EXAMPLE_ROI = "EXAMPLE_ROI"

    /** IntArray(2): the example reference's width and height, which the ROI belongs to; consumed once. */
    const val EXAMPLE_IMAGE_SIZE = "EXAMPLE_IMAGE_SIZE"

    /** The machine-load log to read on open, as if picked on the load card; consumed once. */
    const val PICKED_LOAD_CSV_URI = "PICKED_LOAD_CSV_URI"

    // ── Mechanical test → ResultViewerActivity
    /** Specimen cross-section in mm²; 0 when not entered. */
    const val CROSS_SECTION_MM2 = "CROSS_SECTION_MM2"

    /** True when strain for the stress–strain curve is Exx; false for Eyy. */
    const val LOAD_AXIS_X = "LOAD_AXIS_X"

    /** Signed machine load per frame in newtons; empty when none were imported. */
    const val LOADS_N = "LOADS_N"

    /**
     * [com.sempermechanics.semper.data.mechanical.SpecimenGeometry.toArray] — the bending
     * dimensions, taps and deflection correction; all zero (scale 1) on a tensile test.
     */
    const val SPECIMEN_GEOMETRY = "SPECIMEN_GEOMETRY"

    /** [com.sempermechanics.semper.data.mechanical.CurveCorrection.toArray] — tensile scale and bias. */
    const val CURVE_CORRECTION = "CURVE_CORRECTION"

    // ── Home's Re-run → StaticAnalysisActivity
    /** Home's Re-run: the local id of the saved analysis a new one starts from; consumed once. */
    const val RERUN_SESSION_ID = "RERUN_SESSION_ID"

    /**
     * With [RERUN_SESSION_ID], for an analysis saved without its Re-run inputs:
     * the cache file holding the reference the user picked again, and its name.
     */
    const val RERUN_REFERENCE_PATH = "RERUN_REFERENCE_PATH"
    const val RERUN_REFERENCE_NAME = "RERUN_REFERENCE_NAME"

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

    // ── StaticAnalysisActivity ⇄ BeamEdgeTapActivity
    /** Float: the beam thickness in mm the taps will span. */
    const val BEAM_THICKNESS_MM = "BEAM_THICKNESS_MM"

    /** FloatArray(4): top x, top y, bottom x, bottom y in reference pixels. */
    const val BEAM_EDGE_TAPS = "BEAM_EDGE_TAPS"

    // ── StaticAnalysisActivity  ResultViewerActivity
    const val SESSION_ID = "SESSION_ID"
    const val REF_NAME = "REF_NAME"
    const val REF_PATH = "REF_PATH"
    const val DEF_PATH = "DEF_PATH"
    const val BATCH_DIR_PATH = "BATCH_DIR_PATH"
    const val DEF_FILE_NAMES = "DEF_FILE_NAMES"

    /** A video's frame times in its clip (ms), index-aligned with [DEF_FILE_NAMES]; absent otherwise. */
    const val FRAME_TIMES_MS = "FRAME_TIMES_MS"

    /** Full paths to the raw deformed images (transient cache copies) — best-effort
     *  source for the export's "raw photos" folder; absent when reopened from Home. */
    const val DEF_FILE_PATHS = "DEF_FILE_PATHS"
    const val SUBSET_SIZE = "SUBSET_SIZE"
    const val STRAIN_WINDOW = "STRAIN_WINDOW"
    const val STRAIN_METHOD = "STRAIN_METHOD"

    /** 0: every frame against the first image; N: the reference moved every N frames (incremental DIC). */
    const val REF_UPDATE_INTERVAL = "REF_UPDATE_INTERVAL"
    const val ENGINE_STATS = "ENGINE_STATS"
    const val ROUTING_ERROR = "ROUTING_ERROR"

    /** Marks [ROUTING_ERROR] as the app's own (`AuthRoute.putMessage`); AuthActivity is exported. */
    const val ROUTING_TOKEN = "ROUTING_TOKEN"

    // ── AnalysisViewModel  DicUploadWorker (WorkManager Data)
    const val SESSION_LOCAL_ID = "SESSION_LOCAL_ID"

    // ── DicUploadWorker → UI (terminal-failure output Data)
    /** Human-readable reason attached to a terminal upload Result.failure(). */
    const val UPLOAD_FAIL_REASON = "UPLOAD_FAIL_REASON"

    // ── DicUploadWorker → UI (running progress Data)
    /** "prepare" (bundling reports/GIFs) or "upload" (bytes to Drive). */
    const val UPLOAD_PHASE = "UPLOAD_PHASE"

    /** 0–100 percent for the current [UPLOAD_PHASE]. */
    const val UPLOAD_PERCENT = "UPLOAD_PERCENT"

    /** Bytes moved so far (Long), while the phase counts bytes: an upload's bytes to Drive, a download. */
    const val TRANSFER_BYTES_DONE = "TRANSFER_BYTES_DONE"

    /** The bytes the phase will move (Long), alongside [TRANSFER_BYTES_DONE]. */
    const val TRANSFER_BYTES_TOTAL = "TRANSFER_BYTES_TOTAL"

    /** Smoothed bytes per second (Long), once the worker's meter knows it. */
    const val TRANSFER_BYTES_PER_SECOND = "TRANSFER_BYTES_PER_SECOND"

    // ── DicRestoreWorker / DicBundleDownloadWorker → UI
    /** The [UPLOAD_PHASE] a restore or bundle download reports while fetching. */
    const val PHASE_DOWNLOAD = "download"

    /** Reason on a download worker's terminal Result.failure(). */
    const val DOWNLOAD_ERROR = "error"
    const val STEP = "STEP"
    const val IMG_W = "IMG_W"
    const val IMG_H = "IMG_H"
    const val ROI_X = "ROI_X"
    const val ROI_Y = "ROI_Y"
    const val ROI_W = "ROI_W"
    const val ROI_H = "ROI_H"

    // ── Parameter sweep (SweepStudy) → ResultViewerActivity
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

    // Legacy skip arrays (read fallback). Prefer SWEEP_SKIPPED JSON.
    const val SWEEP_SKIP_SUBSETS = "SWEEP_SKIP_SUBSETS"
    const val SWEEP_SKIP_STEPS = "SWEEP_SKIP_STEPS"
    const val SWEEP_SKIP_STRAIN_WINS = "SWEEP_SKIP_STRAIN_WINS"
    const val SWEEP_SKIP_CODES = "SWEEP_SKIP_CODES"

    /** JSON list of skipped sweep nodes. */
    const val SWEEP_SKIPPED = "SWEEP_SKIPPED"

    /** Why the run stopped early, or 0 when it finished; and what it planned. */
    const val STOP_CODE = "STOP_CODE"
    const val PLANNED_FRAMES = "PLANNED_FRAMES"

    /** Frame the result viewer opens on — a lattice node tap picks one. */
    const val START_FRAME = "START_FRAME"
}
