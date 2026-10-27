package com.sempermechanics.semper.ui.analysis.rerun

import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.frames.ImportedBatch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale

/**
 * A saved analysis's [inputs], copied for a new one: the reference's and the
 * mask's bytes, and the frames in run order, staged in the wizard's own
 * import cache like any picked batch.
 */
internal class LoadedRerun(
    val inputs: RerunInputs,
    val reference: ByteArray,
    val mask: ByteArray?,
    val frames: List<DeformedFrame>,
    /** The saved analysis's region may have been a freehand shape that it never kept; this one is a rectangle. */
    val maskDropped: Boolean = false,
)

/**
 * Reads a saved analysis's Re-run inputs and copies its photos for the
 * wizard. Copies, never moves or points at them: the new analysis is the
 * wizard's to sort, run and discard, and the saved one stays exactly as it
 * is (TD-197).
 */
internal object RerunLoader {

    /**
     * The inputs kept in session folder [sessionDir], with its frames copied
     * into [cacheDir]'s import ([FrameImportHelper.COMMITTED_DIR_NAME]),
     * replacing whatever the wizard had imported. Null when the inputs are not
     * all on this phone ([RerunInputs.readComplete]). Cancellable between
     * frames; a cancelled copy leaves the previous import as it was.
     */
    @WorkerThread
    suspend fun load(sessionDir: File, cacheDir: File): LoadedRerun? {
        val inputs = RerunInputs.readComplete(sessionDir) ?: return null
        val layout = SessionLayout(sessionDir)
        val reference = layout.rerunReference.readBytes()
        val mask = if (inputs.hasMask) layout.rerunMask.readBytes() else null
        return copy(sessionDir, cacheDir, LoadedRerun(inputs, reference, mask, emptyList()))
    }

    /**
     * [record]'s Re-run when it has no inputs kept ([LegacyRerun]): its
     * frames from `raw_deformed/`, copied as [load] copies them, with the
     * reference the user picked again, [reference] (as the engine is handed
     * it) named [referenceName]. Null when its frames are not all here.
     */
    @WorkerThread
    suspend fun loadWithReference(
        record: SessionRecord,
        reference: ByteArray,
        referenceName: String,
        cacheDir: File,
    ): LoadedRerun? {
        if (!LegacyRerun.framesOnPhone(record)) return null
        val inputs = LegacyRerun.inputs(record, referenceName)
        val picked = LoadedRerun(inputs, reference, null, emptyList(), maskDropped = inputs.hasCustomRoi)
        return copy(File(record.sessionDir), cacheDir, picked)
    }

    /** [loaded] with its inputs' frames, under session folder [sessionDir], copied into [cacheDir]'s import. */
    private suspend fun copy(sessionDir: File, cacheDir: File, loaded: LoadedRerun): LoadedRerun {
        val inputs = loaded.inputs
        val staging = FrameImportHelper.createStagingDir(cacheDir)
        try {
            val staged = inputs.frames.mapIndexed { index, frame ->
                currentCoroutineContext().ensureActive()
                val source = File(sessionDir, frame.path)
                val target = File(staging, stagedName(index, frame.name.ifEmpty { source.name }))
                source.copyTo(target, overwrite = true)
                DeformedFrame(
                    path = target.absolutePath,
                    name = frame.name.ifEmpty { source.name },
                    date = frame.date,
                    size = ImageSize(frame.width, frame.height).takeIf { frame.width > 0 && frame.height > 0 },
                    timeMs = frame.timeMs,
                )
            }
            currentCoroutineContext().ensureActive()
            val batch = ImportedBatch(staged, inputs.fromVideo, inputs.videoName)
            val committed = checkNotNull(FrameImportHelper.commitStagedBatch(cacheDir, staging, batch))
            return LoadedRerun(inputs, loaded.reference, loaded.mask, committed.frames, loaded.maskDropped)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** As an import stages a picked image: run order first, then the name, made safe for a path. */
    private fun stagedName(index: Int, name: String): String =
        String.format(Locale.US, "%04d_%s", index, name.replace(Regex("[^a-zA-Z0-9.-]"), "_"))
}
