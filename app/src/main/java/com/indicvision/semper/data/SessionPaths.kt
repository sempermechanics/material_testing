package com.indicvision.semper.data

import java.io.File
import java.util.Locale

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

    /** Engine result filename pattern under a session dir (`frame_0000.dat`). */
    const val FRAME_DAT_FMT = "frame_%04d.dat"

    fun frameDatName(index: Int): String = String.format(Locale.US, FRAME_DAT_FMT, index)

    fun frameDat(sessionDir: File, index: Int): File = File(sessionDir, frameDatName(index))
}
