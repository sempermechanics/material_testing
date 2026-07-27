package com.rafad.indicvisiondic.data

/**
 * On-disk path segments shared by session writers (AnalysisViewModel),
 * upload ([DicUploadWorker]), and restore ([CloudRestore]).
 */
object SessionPaths {
    /** Session-dir subfolder holding the persisted raw deformed originals. */
    const val RAW_DEFORMED_SUBDIR = "raw_deformed"
}
