package com.indicvision.semper.data

/**
 * On-disk path segments shared by session writers (AnalysisViewModel),
 * upload ([DicUploadWorker]), and restore ([CloudRestore]).
 */
object SessionPaths {
    /** Session-dir subfolder holding the persisted raw deformed originals. */
    const val RAW_DEFORMED_SUBDIR = "raw_deformed"

    /** Optional heatmaps / animation trees kept under the session or staging. */
    const val PROCESSED_SUBDIR = "processed"

    /** Transient upload pack folder under the session dir. */
    const val UPLOAD_STAGING_SUBDIR = "upload_staging"
}
