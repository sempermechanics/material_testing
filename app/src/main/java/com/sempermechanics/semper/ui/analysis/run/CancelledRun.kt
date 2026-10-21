package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.sempermechanics.semper.ui.analysis.wizard.RunSession
import com.sempermechanics.semper.ui.analysis.wizard.discardRun
import com.sempermechanics.semper.ui.analysis.wizard.repointDeformedPathsOnMain
import com.sempermechanics.semper.util.AtomicFiles
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Locale

/*
 * A cancelled run keeps nothing (owner decision, 2026-10-15), batch or sweep:
 * not its frames, not a row, not an upload. Its results never left the
 * session's staging folder ([RunSession.staging]); what a batch run did move
 * is the deformed images it reached ([keepDeformedFrame]), which go back to
 * where it found them ([returnMovedFrames]). A batch run that throws before
 * its results are committed is undone the same way ([undoFailedBatch]), and
 * so is one that throws after, before the wizard follows its frames
 * ([undoCommittedBatch]).
 */

/**
 * Throws away a sweep in [session] that the user cancelled, however many
 * combinations it solved ([discardRun]): no row, no upload, and any saved
 * sweep stays exactly as it was. A sweep moves no image before it
 * saves, so there is none to put back. Like a cancelled batch run, it sends
 * no analytics end event, and its outcome names no session, so the wizard
 * stays as it was. [executionTimeMs] is how long it ran.
 */
@WorkerThread
internal fun discardCancelledSweep(
    appContext: Context,
    session: RunSession,
    executionTimeMs: Int,
): BatchAnalysisOutcome {
    discardRun(appContext, session)
    return cancelledOutcome(executionTimeMs)
}

/**
 * How a cancelled run ends: nothing solved is kept, and there is no session
 * to open or for the wizard to take ([BatchAnalysisOutcome.session]).
 */
internal fun cancelledOutcome(executionTimeMs: Int) = BatchAnalysisOutcome(
    engineErrorCode = RunStop.Cancelled.wireCode,
    firstFrameValidPoints = 0,
    totalFrames = 0,
    executionTimeMs = executionTimeMs,
    batchDirPath = "",
)

/**
 * Throws away a batch run in [session] that the user cancelled, or whose view
 * model went with its screen: each image the run moved out of [launched] (to
 * [resolved], index-aligned) goes back, and [discardRun] drops the rest. An
 * image that cannot go back stays where the run put it, and so does the
 * session's directory: the wizard is pointed at it there instead.
 */
@WorkerThread
internal fun AnalysisViewModel.discardCancelledBatch(
    appContext: Context,
    session: RunSession,
    launched: List<String>,
    resolved: List<String>,
) {
    val paths = returnMovedFrames(launched, resolved)
    val stranded = paths != launched
    if (stranded) repointDeformedPathsOnMain(paths)
    discardRun(appContext, session, keepDir = stranded)
}

/**
 * A batch run in [session] that threw before it committed its results (a
 * full disk under `DicFieldIo.write`, say) is undone as a cancelled one is
 * ([discardCancelledBatch]): each image it moved out of [launched] (to
 * [resolved]) goes back, and nothing else of it is kept, so the next Compute
 * finds its frames where the wizard left them. [failure] then goes on up as
 * the run's outcome.
 */
@WorkerThread
internal fun AnalysisViewModel.undoFailedBatch(
    appContext: Context,
    session: RunSession,
    launched: List<String>,
    resolved: List<String>,
    failure: Exception,
): Nothing {
    Timber.w(failure, "Batch run failed before its results were committed; undoing it")
    discardCancelledBatch(appContext, session, launched, resolved)
    throw failure
}

/**
 * A batch run in [session] that threw after it committed its results but
 * before the wizard followed its frames ([repointDeformedPathsOnMain]): in the
 * "Saving…" post, the stale-frame prune, or Re-run's keep past the
 * `IOException` it catches itself (TD-285). The commit had moved the frames
 * into the session while the wizard still named [launched], so it is undone
 * as one that threw before its commit is ([undoFailedBatch]), its committed
 * results first ([RunSession.dropResults]): each image goes back from
 * [resolved], or the wizard is pointed at where it stayed, and nothing of the
 * run is kept. [failure] then goes on up as the run's outcome.
 */
@WorkerThread
internal fun AnalysisViewModel.undoCommittedBatch(
    appContext: Context,
    session: RunSession,
    launched: List<String>,
    resolved: List<String>,
    failure: Exception,
): Nothing {
    Timber.w(failure, "Batch run failed after its results were committed; undoing it")
    session.dropResults()
    discardCancelledBatch(appContext, session, launched, resolved)
    throw failure
}

/**
 * Keeps frame [frameIndex]'s deformed image [source], whose [bytes] the run
 * has already read, in [rawDeformedDir] under [rawName], index-prefixed only
 * if that name is taken; returns the file it is kept as. An image from the
 * import cache is moved: both directories are app-private storage, so that
 * is a rename rather than a second multi-megabyte write, and the fallback
 * writes [bytes] rather than read the file a second time. One in another
 * session's directory (an analysis this wizard saved before) is copied and
 * left where it is: it is that session's own image. [returnMovedFrames]
 * undoes it.
 */
internal fun keepDeformedFrame(
    source: File,
    bytes: ByteArray,
    rawDeformedDir: File,
    rawName: String,
    frameIndex: Int,
): File {
    val target = File(rawDeformedDir, rawName).let {
        if (it.exists()) File(rawDeformedDir, String.format(Locale.US, "%04d_%s", frameIndex, rawName)) else it
    }
    val sessionDir = rawDeformedDir.absoluteFile.parentFile
    val copyOnly = sessionDir != null && SessionPaths.isInOtherSession(source, sessionDir)
    if (copyOnly || !source.renameTo(target)) target.writeBytes(bytes)
    return target
}

/**
 * Undoes what the batch loop's [keepDeformedFrame] did to each frame: one
 * whose image the run moved from its [launched] path to its [resolved] one
 * is moved back, and a copy (the original is still there: an image of
 * another session, or a move that fell back to writing the bytes) is
 * deleted. Returns where each image is now, which is [launched] unless one
 * could not be moved back.
 */
@WorkerThread
internal fun returnMovedFrames(launched: List<String>, resolved: List<String>): List<String> =
    launched.mapIndexed { i, original ->
        val now = resolved.getOrNull(i)
        if (now == null || now == original) return@mapIndexed original
        val source = File(original)
        val moved = File(now)
        when {
            !moved.exists() -> original
            source.exists() -> original.also { moved.delete() }
            putBack(moved, source) -> original
            else -> now
        }
    }

/** Moves [moved] back to [source]; false when it is still at [moved]. */
private fun putBack(moved: File, source: File): Boolean = try {
    source.parentFile?.mkdirs()
    AtomicFiles.promote(moved, source)
    true
} catch (e: IOException) {
    Timber.w(e, "Could not put a cancelled run's frame back at %s", source)
    false
}
