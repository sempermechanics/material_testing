package com.sempermechanics.semper.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.rerun.LabRerun
import com.sempermechanics.semper.ui.analysis.rerun.PickedReference
import com.sempermechanics.semper.ui.analysis.rerun.RerunSource
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisNavHelper
import com.sempermechanics.semper.ui.common.TestTypeSheet
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.media.MediaPickerSheet
import com.sempermechanics.semper.ui.common.media.MediaSourceChooser
import com.sempermechanics.semper.ui.examples.ExamplesActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home's two ways into a new analysis: the + button's source chooser (a
 * photo or a video, from the gallery sheet or the Files picker) and an older
 * analysis's Re-run. It registers Activity Result launchers, so Home builds
 * it as a property, before the screen starts. In Semper Classroom the +
 * button asks **Which test?** first ([TestTypeSheet]), and the type rides
 * the hand-off to the wizard; a Re-run carries its record's lab test
 * ([LabRerun]).
 *
 * @param limitReached opens the limit screen and answers true when the
 *   account is at its analysis limit ([HomeQuotaCard.openLimitScreenIfReached]).
 */
@MainThread
internal class HomeNewAnalysis(
    private val activity: AppCompatActivity,
    private val limitReached: () -> Boolean,
) {

    /** Files tab: Storage Access Framework (Drive, storage, DNG). */
    private val pickDocument =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            routePickedMedia(uri)
        }

    private var mediaPicker: MediaPickerSheet? = null

    /**
     * The test type picked on the sheet, waiting for the media picker to
     * finish. Saved across recreation ([saveState] / [restoreState]) because
     * the SAF picker can kill Home while it is open, and the wizard must not
     * open typeless.
     */
    private var pendingTestType: TestType? = null

    /** An older analysis's Re-run: its reference picked again. Registers launchers, so built with the screen. */
    private val rerunReference = RerunReferencePicker(activity, onPicked = ::openRerun)
    private val requestMediaPermission =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            mediaPicker?.onPermissionResult()
        }

    /**
     * The + button, when new work may start: which test first (the wizard
     * decides on open whether to ask for machine loads, so it has to know
     * before any media is picked), then the source chooser.
     */
    fun start() {
        if (!mayStart()) return
        val examples = { activity.startActivity(ExamplesActivity.intent(activity)) }
        TestTypeSheet.show(activity, onExamples = examples) { type ->
            pendingTestType = type
            showSourceChooser()
        }
    }

    /** Keeps [pendingTestType] across a recreation of Home; call from `onSaveInstanceState`. */
    fun saveState(outState: Bundle) {
        pendingTestType?.let { outState.putString(IntentKeys.TEST_TYPE, it.wireName) }
    }

    /** Takes back what [saveState] kept; call from `onCreate`. */
    fun restoreState(savedInstanceState: Bundle?) {
        pendingTestType = TestType.fromWire(savedInstanceState?.getString(IntentKeys.TEST_TYPE))
    }

    /**
     * Whether new work may start, having opened the screen that says why not
     * when it may not: the + button and Re-run both ask. Two independent
     * reasons: the seat check is first because an institution member is
     * licensed, so the quota check below is false for them by definition and
     * would wave them through. At the account's analysis limit, new work is
     * blocked behind the persistent limit screen (email support) instead of
     * failing on upload.
     */
    private fun mayStart(): Boolean = when {
        // A build the backend no longer supports: its gate screen is opened.
        !AnalysisNavHelper.ensureUpToDate(activity) -> false
        LicenseEntitlements.isSeatRequiredToStart(activity) -> {
            AnalysisNavHelper.openSeatRequired(activity)
            false
        }
        else -> !limitReached()
    }

    /**
     * Re-run: opens the wizard on a new analysis copied from [record]'s
     * photos and settings (owner decision, 2026-10-15: a saved analysis is
     * never re-run in place). One saved before Re-run kept its reference, or
     * restored, asks for its reference again ([RerunReferencePicker]); one
     * whose frames are not here (freed up) says so. The new analysis counts
     * toward the limit like any other, so the gates come first.
     */
    fun rerun(record: SessionRecord) {
        activity.lifecycleScope.launch {
            val source = withContext(Dispatchers.IO) { RerunSource.of(record) }
            when {
                source == RerunSource.MISSING -> Dialogs.info(
                    activity,
                    R.string.rerun_unavailable_title,
                    R.string.rerun_photos_missing_body,
                )
                !mayStart() -> Unit
                source == RerunSource.NEEDS_REFERENCE -> rerunReference.ask(record)
                else -> openRerun(record, null)
            }
        }
    }

    /** The wizard on [record]'s Re-run, with the reference picked again when [picked]. */
    private fun openRerun(record: SessionRecord, picked: PickedReference?) {
        val intent = Intent(activity, StaticAnalysisActivity::class.java)
            .putExtra(IntentKeys.RERUN_SESSION_ID, record.id)
        // A tensile or bending analysis re-runs as one, not as plain DIC (TD-220).
        LabRerun.of(record)?.putOn(intent)
        if (picked != null) {
            intent.putExtra(IntentKeys.RERUN_REFERENCE_PATH, picked.file.absolutePath)
                .putExtra(IntentKeys.RERUN_REFERENCE_NAME, picked.name)
        }
        activity.startActivity(intent)
    }

    /** In-sheet Images gallery; Files opens SAF for Drive / storage / DNG. */
    private fun showSourceChooser() {
        mediaPicker = MediaSourceChooser.show(
            activity = activity,
            mode = MediaSourceChooser.Mode.HOME_REFERENCE,
            requestPermission = {
                requestMediaPermission.launch(
                    MediaSourceChooser.requiredPermissions(includeVideo = true),
                )
            },
            onBrowseSaf = { pickDocument.launch(arrayOf("image/*", "video/*")) },
            onPicked = { uris -> routePickedMedia(uris.firstOrNull()) },
        )
    }

    /**
     * Route a picked photo/video into the analysis screen. Shared by both source
     * pickers so the two entry points behave identically; the mime type decides
     * whether we hand off a single reference image or a video to sample frames
     * from.
     */
    private fun routePickedMedia(uri: Uri?) {
        if (uri == null) return
        val mime = activity.contentResolver.getType(uri) ?: ""
        val intent = Intent(activity, StaticAnalysisActivity::class.java)
        pendingTestType?.let { intent.putExtra(IntentKeys.TEST_TYPE, it.wireName) }
        if (mime.startsWith("video/")) {
            intent.putExtra(IntentKeys.PICKED_VIDEO_URI, uri.toString())
        } else {
            intent.putExtra(IntentKeys.PICKED_REF_URI, uri.toString())
        }
        activity.startActivity(intent)
    }
}
