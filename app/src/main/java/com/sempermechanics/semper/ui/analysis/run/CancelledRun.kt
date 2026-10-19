package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import androidx.annotation.WorkerThread
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

/*
 * A cancelled run keeps nothing (owner decision, 2026-10-15), batch or sweep:
 * not its frames, not a row, not an upload. Its results never left the
 * session's staging folder ([RunSession.staging]); what a batch run did move
 * is the deformed images it reached, which go back to where it found them.
 */

/**
 * Throws away a sweep in [session] that the user cancelled, however many
 * combinations it solved ([discardRun]): no row, no upload, and any saved
 * sweep stays exactly as it was. A sweep moves no image before it
 * saves, so there is none to put back. Like a cancelled batch run, it sends
 * no analytics end event. [executionTimeMs] is how long it ran.
 */
@WorkerThread
internal fun AnalysisViewModel.discardCancelledSweep(
    appContext: Context,
    session: RunSession,
    executionTimeMs: Int,
): BatchAnalysisOutcome {
    discardRun(appContext, session)
    return cancelledOutcome(executionTimeMs)
}

/** How a cancelled run ends: nothing solved is kept, and there is no session to open. */
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
